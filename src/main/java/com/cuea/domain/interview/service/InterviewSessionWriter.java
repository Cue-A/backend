package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiSessionStartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 면접 세션의 짧은 트랜잭션 전용 공용 writer.
 *
 * <p>세션 시작({@link InterviewStartService})과 답변 진행({@link InterviewAnswerService})
 * 양쪽이 함께 씁니다. AI 호출(폴링 최대 60~90초)을 트랜잭션 밖에 두기 위해 DB
 * 읽기·쓰기를 여기 모았습니다. 각 메서드가 독립된 짧은 트랜잭션이며, 호출 순서는
 * 각 진입 서비스가 정합니다.
 */
@Component
@RequiredArgsConstructor
public class InterviewSessionWriter {

    private final InterviewSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;

    @Transactional
    public InterviewSession createSession(User user, Document document, Company company,
                                           InterviewStartRequest request,
                                           AiSessionStartResponse aiResponse,
                                           int fallbackQuestionCount) {
        InterviewSession session = InterviewSession.builder()
                .sessionId(aiResponse.sessionId())
                .user(user)
                .company(company)
                .document(document)
                .mode("PRACTICE")
                .jobRole(request.jobRole())
                .questionCount(aiResponse.questionTotal() != null
                        ? aiResponse.questionTotal()
                        : fallbackQuestionCount)
                .persona(request.persona())
                .hideQuestionText(Boolean.TRUE.equals(request.hideQuestionText()))
                .status(SessionStatus.IN_PROGRESS)
                .build();
        return sessionRepository.save(session);
    }

    /**
     * 세션 시작 첫 질문 저장. <b>세션이 아직 {@code IN_PROGRESS} 일 때만 저장</b>합니다.
     * 사용자 abort 등으로 이미 종료된 세션이면 저장하지 않고 {@code null} 을 돌려줍니다(#25).
     * 상태 확인과 저장이 같은 트랜잭션 안에 있어, 확인-저장 사이에 abort 가 끼어드는
     * 창을 (같은 커넥션 기준으로) 닫습니다.
     */
    @Transactional
    public Question saveFirstQuestion(String sessionId, AiQuestionResult result) {
        return saveIfInProgress(sessionId, result);
    }

    /**
     * 답변 처리 결과로 받은 다음 질문(주질문·꼬리질문·되묻기)을 저장합니다.
     *
     * <p><b>수신 즉시 저장합니다.</b> 프론트 push 전에 저장해야 사용자가 그 사이에
     * 나가도 기록이 남습니다. {@code session_end} 나 알 수 없는 type 은 질문이 아니므로
     * {@link #toQuestionType} 에서 거부합니다(호출 측에서 미리 걸러야 합니다).
     *
     * <p><b>세션이 아직 {@code IN_PROGRESS} 일 때만 저장</b>합니다. 사용자 abort 로 이미
     * {@code ABORTED}(또는 {@code COMPLETED}) 인 세션에 늦게 도착한 결과는 저장하지 않고
     * {@code null} 을 돌려줍니다. 호출부는 {@code null} 이면 push 도 하지 않습니다(#25).
     */
    @Transactional
    public Question saveNextQuestion(String sessionId, AiQuestionResult result) {
        return saveIfInProgress(sessionId, result);
    }

    /**
     * 답변 미디어(오디오·영상 object key, timeout 여부)를 대상 질문에 붙입니다.
     *
     * <p>DB 에는 object key 만 저장합니다. presigned URL 은 만료되므로 저장하지
     * 않습니다. 영상은 카메라 미사용 시 null 입니다.
     */
    @Transactional
    public void attachAnswerMedia(String sessionId, String questionId,
                                  String audioObjectKey, String videoObjectKey,
                                  boolean isTimeout) {
        Question question = questionRepository.findBySessionIdAndQuestionId(sessionId, questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
        question.attachAnswerMedia(audioObjectKey, videoObjectKey, isTimeout);
    }

    /**
     * {@code session_end} 수신. 세션이 {@code IN_PROGRESS} 일 때만 COMPLETED 로 전이합니다.
     *
     * @return 이번 호출로 실제 COMPLETED 로 전이했으면 {@code true}. 이미 abort/complete 된
     *         세션이라 전이하지 않았으면 {@code false}. 호출부는 {@code false} 면 session_end
     *         push 도 하지 않습니다(#25).
     */
    @Transactional
    public boolean completeSession(String sessionId) {
        InterviewSession session = sessionRepository.findBySessionIdForInternal(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.isInProgress()) {
            return false;
        }
        session.complete();
        return true;
    }

    /**
     * 세션이 {@code IN_PROGRESS} 일 때만 질문을 저장합니다. 상태 확인과 저장을 한 트랜잭션에
     * 두어, 사용자 abort 이후 늦게 도착한 결과가 DB 에 새로 쌓이지 않게 합니다(#25).
     *
     * @return 저장한 {@link Question}. 세션이 이미 종료돼 저장하지 않았으면 {@code null}.
     */
    private Question saveIfInProgress(String sessionId, AiQuestionResult result) {
        InterviewSession session = sessionRepository.findBySessionIdForInternal(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.isInProgress()) {
            return null;
        }
        return saveQuestion(sessionId, result);
    }

    private Question saveQuestion(String sessionId, AiQuestionResult result) {
        Question question = Question.builder()
                .sessionId(sessionId)
                .questionId(result.questionId())
                .type(toQuestionType(result.type()))
                .text(result.text())
                .audioUrl(result.audioUrl())
                .category(result.category())
                .difficulty(result.difficulty())
                .reaskOf(result.reaskOf())
                .spareTopic(Boolean.TRUE.equals(result.isSpareTopic()))
                .replay(Boolean.TRUE.equals(result.isReplay()))
                .questionNumber(result.questionNumber() != null ? result.questionNumber() : 0)
                .topicIndex(result.topicIndex() != null ? result.topicIndex() : 0)
                .build();

        return questionRepository.save(question);
    }

    /** 세션 시작 폴링이 타임아웃·실패했을 때 세션을 중단 상태로 정리합니다. */
    @Transactional
    public void markAborted(String sessionId) {
        sessionRepository.findBySessionIdForInternal(sessionId)
                .ifPresent(InterviewSession::abort);
    }

    private QuestionType toQuestionType(String aiType) {
        if (AiQuestionResult.TYPE_QUESTION.equals(aiType)) {
            return QuestionType.QUESTION;
        }
        if (AiQuestionResult.TYPE_FOLLOWUP.equals(aiType)) {
            return QuestionType.FOLLOWUP;
        }
        if (AiQuestionResult.TYPE_REASK.equals(aiType)) {
            return QuestionType.REASK;
        }
        // session_end / null / 알 수 없는 type 은 질문으로 저장할 수 없습니다.
        // 첫 질문 자리에 이런 값이 오면 계약 위반이므로 UNEXPECTED_AI_RESPONSE 로 막습니다.
        throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE,
                "질문 타입이 아닌 AI 응답입니다: type=" + aiType);
    }
}
