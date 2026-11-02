package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.InterviewRecordingsResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.infrastructure.file.ObjectKeys;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 면접 다시보기 재생목록. {@code GET /api/interviews/{sessionId}/recordings}.
 *
 * <p>조립 규칙은 {@code ReportRequestAssembler} 와 같습니다. 나간 순서대로, 답변 녹음이
 * 없는 질문은 빼고, 영상 key 가 없으면 null. 받는 쪽이 AI 가 아니라 사용자일 뿐입니다.
 *
 * <p>presigned URL 발급은 서명 계산이라 네트워크를 타지 않아 읽기 트랜잭션 안에서 합니다.
 * S3 에 파일이 있는지({@code headObject})는 보지 않습니다. 답변 제출 때 이미 확인했고,
 * 보면 문항마다 S3 호출이 나갑니다.
 */
@Service
@RequiredArgsConstructor
public class InterviewRecordingService {

    private final InterviewSessionQueryService sessionQueryService;
    private final QuestionRepository questionRepository;
    private final PresignedUrlIssuer presignedUrlIssuer;

    /** 완료된 본인 세션만. 없거나 남의 세션은 404, 진행 중·중단은 409 입니다. */
    @Transactional(readOnly = true)
    public InterviewRecordingsResponse getRecordings(String userId, String sessionId) {
        InterviewSession session = sessionQueryService.getOwnedSession(sessionId, userId);
        checkCompleted(session.getStatus());

        List<InterviewRecordingsResponse.Item> items =
                questionRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                        .filter(question -> isPresent(question.getAnswerAudioObjectKey()))
                        .map(this::toItem)
                        .toList();
        return InterviewRecordingsResponse.of(sessionId, items);
    }

    private void checkCompleted(SessionStatus status) {
        if (status == SessionStatus.COMPLETED) {
            return;
        }
        throw new BusinessException(status == SessionStatus.ABORTED
                ? ErrorCode.SESSION_ABORTED
                : ErrorCode.SESSION_NOT_COMPLETED);
    }

    private InterviewRecordingsResponse.Item toItem(Question question) {
        String videoKey = question.getAnswerVideoObjectKey();
        return new InterviewRecordingsResponse.Item(
                question.getQuestionId(),
                question.getQuestionNumber(),
                question.getType().name(),
                question.getText(),
                questionAudioUrl(question),
                presignedUrlIssuer.issueRecordingDownload(question.getAnswerAudioObjectKey()),
                isPresent(videoKey) ? presignedUrlIssuer.issueRecordingDownload(videoKey) : null);
    }

    /**
     * 질문 push 와 같이 AI 가 준 URL 은 음성이 있는지만 보고, 서명은 계약 key 로 합니다.
     * 음성이 없거나(TTS 실패) 서명이 실패하면 null 입니다.
     */
    private String questionAudioUrl(Question question) {
        if (question.getAudioUrl() == null) {
            return null;
        }
        return presignedUrlIssuer.issueQuestionAudioPlayback(
                        ObjectKeys.questionAudio(question.getSessionId(), question.getQuestionId()))
                .orElse(null);
    }

    private static boolean isPresent(String objectKey) {
        return objectKey != null && !objectKey.isBlank();
    }
}
