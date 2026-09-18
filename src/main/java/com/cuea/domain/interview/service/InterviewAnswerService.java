package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.request.AnswerSubmitRequest;
import com.cuea.domain.interview.dto.request.AnswerUploadUrlRequest;
import com.cuea.domain.interview.dto.response.AnswerUploadUrlResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiAnswerSubmitRequest;
import com.cuea.infrastructure.file.FileValidator;
import com.cuea.infrastructure.file.ObjectKeys;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
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
 *       {@link FileValidator} 를 재사용합니다.</li>
 *   <li><b>{@link #submit} (동기, HTTP 요청 스레드)</b> — 소유권 확인 → object key 를
 *       질문에 저장 → AI 접근용 presigned GET URL 생성 → {@code POST
 *       /ai/sessions/{id}/answers} 로 제출해 task_id 수신 → 폴링을
 *       {@link InterviewAnswerPoller} 에 위임하고 즉시 반환.</li>
 * </ol>
 *
 * <p>AI 계약은 {@code task_id} 즉시 반환 + 폴링(최대 60초) 모델입니다. 폴링을 HTTP
 * 요청 스레드에서 기다리면 응답이 그만큼 늦어지므로, 세션 시작과 동일하게 폴링은
 * {@link InterviewAnswerPoller}(가상 스레드 {@code @Async})로 넘깁니다.
 *
 * <p><b>{@code @Transactional} 을 이 클래스에 붙이지 않습니다.</b> DB 쓰기는 짧은
 * 트랜잭션을 가진 {@link InterviewSessionWriter} 로 위임하고 그 사이에 AI 호출을
 * 배치합니다. docs/01-conventions.md 의 트랜잭션 항목 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewAnswerService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewSessionWriter sessionWriter;
    private final FileValidator fileValidator;
    private final PresignedUrlIssuer presignedUrlIssuer;
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

        // DB 에는 object key 만 저장합니다(presigned URL 은 만료되므로 저장 안 함).
        // answer_is_timeout 도 여기서 함께 저장합니다.
        sessionWriter.attachAnswerMedia(sessionId, request.questionId(),
                audioKey, videoKey, request.isTimeout());

        // AI 호출 직전에 object key → presigned GET URL 로 변환합니다.
        // 영상은 카메라 미사용 시 null 이며, 계약상 video_url 은 null 을 허용합니다.
        String audioUrl = presignedUrlIssuer.issueRecordingDownload(audioKey);
        String videoUrl = videoKey == null
                ? null
                : presignedUrlIssuer.issueRecordingDownload(videoKey);

        AiAnswerSubmitRequest aiRequest = new AiAnswerSubmitRequest(
                request.questionId(), audioUrl, videoUrl, request.isTimeout());

        String taskId = aiClient.submitAnswer(sessionId, aiRequest);
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE,
                    "AI 가 답변 task_id 를 주지 않았습니다");
        }

        // 폴링(최대 60초)은 HTTP 요청 스레드에서 기다리지 않고 백그라운드로 넘깁니다.
        answerPoller.pollAndDeliver(sessionId, taskId);
    }

    /**
     * 세션을 소유자 기준으로 조회하고, 진행 중인지 확인합니다.
     *
     * <p>이미 종료·중단된 세션에는 답변을 받지 않습니다. error_code 별 재시도·중복
     * 제출 정책은 Issue #25 범위이므로, 여기서는 상태만 최소 검증합니다.
     */
    private InterviewSession requireOwnedActiveSession(String userId, String sessionId) {
        InterviewSession session = sessionRepository
                .findBySessionIdAndUser_UserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        return session;
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
