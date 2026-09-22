package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.request.AnswerSubmitRequest;
import com.cuea.domain.interview.dto.request.AnswerUploadUrlRequest;
import com.cuea.domain.interview.dto.response.AnswerUploadUrlResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiAnswerSubmitRequest;
import com.cuea.infrastructure.file.FileValidator;
import com.cuea.infrastructure.file.ObjectKeys;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import com.cuea.infrastructure.file.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 답변 제출 흐름의 진입점. 세션 시작({@link InterviewStartService})과 같은 2단계
 * 구조입니다.
 *
 * <ol>
 *   <li><b>{@link #issueUploadUrls} (동기)</b> — 답변 오디오·영상 업로드용 presigned
 *       PUT URL 발급. object key 규칙은 {@link ObjectKeys}, 검증은
 *       {@link FileValidator} 를 재사용합니다. URL 발급 전에 {@code (sessionId,
 *       questionId)} 질문이 실제 존재하는지 확인해, 임의 questionId 로 orphan 객체를
 *       만들 수 없게 합니다.</li>
 *   <li><b>{@link #submit} (동기, HTTP 요청 스레드)</b> — 소유권·상태 확인 → object key
 *       namespace 검증 → S3 객체 존재 확인 → object key 를 질문에 저장 → AI 접근용
 *       presigned GET URL 생성 → {@code POST /ai/sessions/{id}/answers} 로 제출해
 *       task_id 수신 → 폴링을 {@link InterviewAnswerPoller} 에 위임하고 즉시 반환.</li>
 * </ol>
 *
 * <p>AI 계약은 {@code task_id} 즉시 반환 + 폴링(최대 60초) 모델입니다. 폴링을 HTTP
 * 요청 스레드에서 기다리면 응답이 그만큼 늦어지므로, 세션 시작과 동일하게 폴링은
 * {@link InterviewAnswerPoller}(가상 스레드 {@code @Async})로 넘깁니다.
 *
 * <p><b>{@code @Transactional} 을 이 클래스에 붙이지 않습니다.</b> DB 쓰기는 짧은
 * 트랜잭션을 가진 {@link InterviewSessionWriter} 로 위임하고 그 사이에 AI 호출을
 * 배치합니다. docs/01-conventions.md 의 트랜잭션 항목 참고.
 *
 * <p><b>범위:</b> 동일 질문 중복 제출 방지·in-flight idempotency·media 저장 시점·AI
 * error_code 별 재시도는 정책 결정이 필요해 Issue #25 로 분리합니다. 이 클래스는 정상
 * 흐름 완성과 최소 신뢰 경계/정리에 집중합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewAnswerService {

    private final InterviewSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final InterviewSessionWriter sessionWriter;
    private final FileValidator fileValidator;
    private final PresignedUrlIssuer presignedUrlIssuer;
    private final S3StorageService storageService;
    private final AiClient aiClient;
    private final InterviewAnswerPoller answerPoller;

    /**
     * 답변 오디오(필수)·영상(선택)의 presigned PUT URL 을 발급합니다. 실제 업로드는
     * 프론트가 이 URL 로 직접 합니다. 반환한 object key 를 {@link #submit} 에 그대로
     * 실어 보내야 합니다.
     */
    public AnswerUploadUrlResponse issueUploadUrls(String userId, String sessionId,
                                                   AnswerUploadUrlRequest request) {
        requireOwnedActiveSession(userId, sessionId);
        // 임의 questionId 로 PUT URL(→ orphan 객체)을 만들 수 없게, 발급 전에 해당
        // 질문이 이 세션에 실제 존재하는지 확인합니다. 다른 세션/임의 ID 는 거부됩니다.
        requireQuestionExists(sessionId, request.questionId());

        fileValidator.validateAnswerAudio(
                request.audioFileName(), request.audioMimeType(), request.audioSize());
        String audioExt = fileValidator.extensionOf(request.audioFileName());
        String audioKey = ObjectKeys.answerAudio(sessionId, request.questionId(), audioExt);
        String audioUrl = presignedUrlIssuer.issueUpload(audioKey, request.audioMimeType());

        String videoKey = null;
        String videoUrl = null;
        if (request.hasVideo()) {
            fileValidator.validateAnswerVideo(
                    request.videoFileName(), request.videoMimeType(),
                    request.videoSize() != null ? request.videoSize() : 0);
            String videoExt = fileValidator.extensionOf(request.videoFileName());
            videoKey = ObjectKeys.answerVideo(sessionId, request.questionId(), videoExt);
            videoUrl = presignedUrlIssuer.issueUpload(videoKey, request.videoMimeType());
        }

        return new AnswerUploadUrlResponse(audioKey, audioUrl, videoKey, videoUrl);
    }

    /**
     * 답변을 제출하고 즉시 반환합니다. 다음 질문·되묻기·세션 종료는 백그라운드 폴링
     * 후 WebSocket 으로 전달되므로, 프론트는 {@code /ws/interviews/{sessionId}} 연결을
     * 유지한 채 결과를 기다립니다.
     */
    public void submit(String userId, String sessionId, AnswerSubmitRequest request) {
        requireOwnedActiveSession(userId, sessionId);

        // 신뢰 경계: 프론트가 되돌려준 object key 를 그대로 믿지 않습니다. 해당 세션·
        // 질문의 정규 namespace(sessions/{sessionId}/answers/{questionId}...) 에 속하는
        // key 만 허용해, 다른 세션·다른 질문·임의 경로(resumes/*, 다른 유저 등)의 key 로
        // presigned GET 을 발급받는 것을 막습니다.
        String audioKey = requireValidAnswerAudioKey(sessionId, request.questionId(), request.audioObjectKey());
        String videoKey = requireValidAnswerVideoKey(sessionId, request.questionId(), request.videoObjectKey());

        // 실제 업로드가 끝나지 않은 key 로 답변을 진행하지 않습니다. AI 는 뒤늦게
        // 객체 없음을 발견하므로, AI 호출 전에 S3 에 객체가 있는지 확인해 거부합니다.
        requireUploaded(audioKey);
        if (videoKey != null) {
            requireUploaded(videoKey);
        }

        // isTimeout 은 @NotNull 로 검증되므로 여기서는 non-null 입니다(누락은 컨트롤러
        // validation 에서 이미 거부됨). 방어적으로 언박싱합니다.
        boolean isTimeout = Boolean.TRUE.equals(request.isTimeout());

        // DB 에는 object key 만 저장합니다(presigned URL 은 만료되므로 저장 안 함).
        // answer_is_timeout 도 여기서 함께 저장합니다.
        sessionWriter.attachAnswerMedia(sessionId, request.questionId(), audioKey, videoKey, isTimeout);

        // AI 호출 직전에 object key → presigned GET URL 로 변환합니다.
        // 영상은 카메라 미사용 시 null 이며, 계약상 video_url 은 null 을 허용합니다.
        String audioUrl = presignedUrlIssuer.issueRecordingDownload(audioKey);
        String videoUrl = videoKey == null
                ? null
                : presignedUrlIssuer.issueRecordingDownload(videoKey);

        AiAnswerSubmitRequest aiRequest = new AiAnswerSubmitRequest(
                request.questionId(), audioUrl, videoUrl, isTimeout);

        String taskId = aiClient.submitAnswer(sessionId, aiRequest);
        if (taskId == null || taskId.isBlank()) {
            // 계약 위반: 답변은 접수됐을 수 있는데 폴링 handle 이 없습니다. AI 와 우리
            // 상태가 어긋났을 수 있어, 폴러 제출 실패와 동일하게 세션을 정리합니다.
            BusinessException e = new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE,
                    "AI 가 답변 task_id 를 주지 않았습니다");
            log.warn("AI 가 답변 task_id 를 주지 않아 세션을 정리합니다 sessionId={}", sessionId);
            abortAiSessionQuietly(sessionId, e);
            markSessionAbortedQuietly(sessionId, e);
            throw e;
        }

        // 폴링(최대 60초)은 백그라운드로 넘깁니다. @Async 태스크 제출 자체가 실패하면
        // (예: 종료 중 TaskRejectedException) AI task 는 이미 수락됐고 폴러가 시작조차 못
        // 해 세션이 영구 잔류합니다. 세션 시작(InterviewStartService)과 동일하게, AI
        // 세션을 abort 하고 우리 세션을 ABORTED 로 정리한 뒤 원본 예외를 다시 던집니다.
        try {
            answerPoller.pollAndDeliver(sessionId, taskId);
        } catch (RuntimeException e) {
            log.warn("답변 폴링 시작에 실패해 세션을 정리합니다 sessionId={}", sessionId, e);
            abortAiSessionQuietly(sessionId, e);
            markSessionAbortedQuietly(sessionId, e);
            throw e;
        }
    }

    /**
     * 사용자가 진행 중인 면접을 중단합니다. (Issue #25)
     *
     * <p>소유자 기준으로 세션을 조회한 뒤, AI 세션을 중단하고 우리 세션을
     * {@code ABORTED} 로 정리합니다. <b>AI 중단 호출이 실패해도 로컬 상태 정리는
     * 반드시 수행</b>해, AI 서버가 죽었을 때 세션이 {@code IN_PROGRESS} 로 영구
     * 잔류하지 않게 합니다.
     *
     * <ul>
     *   <li>소유하지 않은/없는 세션 → {@code SESSION_NOT_FOUND}</li>
     *   <li>이미 {@code ABORTED} → 멱등 no-op (AI·로컬 재정리 안 함)</li>
     *   <li>{@code COMPLETED} → {@code SESSION_ENDED}. 완료된 세션을 중단으로
     *       역전시키지 않습니다(상태 가드도 이를 막지만, 사용자에게 명확히 알립니다).</li>
     * </ul>
     */
    public void abort(String userId, String sessionId) {
        InterviewSession session = sessionRepository
                .findBySessionIdAndUser_UserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        SessionStatus status = session.getStatus();
        if (status == SessionStatus.ABORTED) {
            // 이미 중단됨. 재중단은 멱등하게 아무 것도 하지 않는다.
            return;
        }
        if (status != SessionStatus.IN_PROGRESS) {
            // COMPLETED 등 종료된 세션은 중단으로 되돌리지 않는다.
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }

        // AI 세션을 먼저 중단한다. 실패해도(예: AI 서버 다운) 로컬 정리는 이어서 수행한다.
        try {
            aiClient.abortSession(sessionId);
        } catch (RuntimeException e) {
            log.warn("사용자 abort 중 AI 세션 중단 실패 sessionId={}. 로컬 정리는 계속합니다.",
                    sessionId, e);
        }
        sessionWriter.markAborted(sessionId);
    }

    /** AI 세션 중단을 시도하되, 실패해도 원인 예외({@code cause})를 덮지 않습니다. */
    private void abortAiSessionQuietly(String sessionId, RuntimeException cause) {
        try {
            aiClient.abortSession(sessionId);
        } catch (RuntimeException cleanupError) {
            cause.addSuppressed(cleanupError);
            log.warn("AI 세션 중단 실패 sessionId={}", sessionId, cleanupError);
        }
    }

    /** 우리 세션을 ABORTED 로 정리하되, 실패해도 원인 예외({@code cause})를 덮지 않습니다. */
    private void markSessionAbortedQuietly(String sessionId, RuntimeException cause) {
        try {
            sessionWriter.markAborted(sessionId);
        } catch (RuntimeException cleanupError) {
            cause.addSuppressed(cleanupError);
            log.warn("세션 ABORTED 처리 실패 sessionId={}", sessionId, cleanupError);
        }
    }

    /**
     * 세션을 소유자 기준으로 조회하고, 진행 중인지 확인합니다.
     *
     * <p>정상 종료(COMPLETED)와 중단(ABORTED)을 구분해 각각 {@code SESSION_ENDED} ·
     * {@code SESSION_ABORTED} 로 알립니다. 클라이언트가 두 상황을 다르게 안내할 수
     * 있어야 하기 때문입니다. error_code 별 재시도·중복 제출 정책은 Issue #25 범위이므로
     * 여기서는 상태 구분까지만 합니다.
     */
    private InterviewSession requireOwnedActiveSession(String userId, String sessionId) {
        InterviewSession session = sessionRepository
                .findBySessionIdAndUser_UserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        SessionStatus status = session.getStatus();
        if (status == SessionStatus.ABORTED) {
            throw new BusinessException(ErrorCode.SESSION_ABORTED);
        }
        if (status != SessionStatus.IN_PROGRESS) {
            // COMPLETED 등 진행 중이 아닌 나머지는 이미 종료된 세션으로 취급합니다.
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        return session;
    }

    /**
     * {@code (sessionId, questionId)} 질문이 이 세션에 실제 존재하는지 확인합니다.
     * 없으면 {@code QUESTION_NOT_FOUND}. 소유권은 세션으로 이미 확인했으므로 여기서는
     * 소속만 봅니다.
     */
    private void requireQuestionExists(String sessionId, String questionId) {
        if (questionRepository.findBySessionIdAndQuestionId(sessionId, questionId).isEmpty()) {
            throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
        }
    }

    /** object 가 실제 업로드됐는지 확인합니다. 없으면 업로드 미완료로 거부합니다. */
    private void requireUploaded(String objectKey) {
        if (!storageService.exists(objectKey)) {
            throw new BusinessException(ErrorCode.UPLOAD_NOT_COMPLETED,
                    "업로드가 완료되지 않은 답변 파일입니다");
        }
    }

    /**
     * 답변 오디오 key(필수)가 이 세션·질문의 정규 namespace 에 속하는지 검증합니다.
     * 어긋나면 {@code INVALID_REQUEST} 로 거부합니다.
     */
    private String requireValidAnswerAudioKey(String sessionId, String questionId, String key) {
        if (!ObjectKeys.isValidAnswerAudioKey(sessionId, questionId, key)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "답변 오디오 key 가 이 질문의 경로와 일치하지 않습니다");
        }
        return key;
    }

    /**
     * 답변 영상 key 를 검증합니다. 카메라 미사용이면 null 이 정상이며 그대로 통과합니다.
     * 값이 있으면 이 세션·질문의 정규 namespace 에 속해야 합니다.
     */
    private String requireValidAnswerVideoKey(String sessionId, String questionId, String key) {
        if (key == null) {
            return null;
        }
        if (!ObjectKeys.isValidAnswerVideoKey(sessionId, questionId, key)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "답변 영상 key 가 이 질문의 경로와 일치하지 않습니다");
        }
        return key;
    }
}
