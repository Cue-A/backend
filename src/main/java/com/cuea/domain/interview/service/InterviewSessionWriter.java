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
 * {@link InterviewStartService} 가 쓰는 짧은 트랜잭션 전용 컴포넌트.
 *
 * <p>AI 호출(세션 시작 폴링 최대 90초)을 트랜잭션 밖에 두기 위해 DB 읽기·쓰기를
 * 여기 모았습니다. 각 메서드가 독립된 트랜잭션이며, 호출 순서는
 * {@code InterviewStartService.start()} 가 정합니다.
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
                .hideQuestionText(false)
                .status(SessionStatus.IN_PROGRESS)
                .build();
        return sessionRepository.save(session);
    }

    @Transactional
    public Question saveFirstQuestion(String sessionId, AiQuestionResult result) {
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
