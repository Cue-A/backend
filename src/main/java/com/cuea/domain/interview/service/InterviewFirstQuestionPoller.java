package com.cuea.domain.interview.service;

import com.cuea.common.config.AsyncConfig;
import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.Question;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.AiErrorTranslator;
import com.cuea.infrastructure.ai.AiPoller;
import com.cuea.infrastructure.ai.AiProperties;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;
import com.cuea.infrastructure.websocket.SessionSocketHandler;
import com.cuea.infrastructure.websocket.message.ErrorPushMessage;
import com.cuea.infrastructure.websocket.message.ProgressPushMessage;
import com.cuea.infrastructure.websocket.message.ProgressStage;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 세션 시작 후 첫 질문을 백그라운드에서 폴링·저장·전달합니다.
 *
 * <p><b>별도 빈으로 분리한 이유:</b> {@code @Async} 는 Spring AOP 프록시를 통해야
 * 동작합니다. {@link InterviewStartService} 안에 두고 self-invocation 하면
 * 프록시를 거치지 않아 그냥 동기 실행돼 버립니다. 그래서 폴링 단계만 이 컴포넌트로
 * 떼어냈습니다.
 *
 * <p>AI 계약은 {@code task_id} 즉시 반환 + 폴링 모델이라, HTTP 요청 스레드는
 * {@code InterviewStartService.start()} 에서 세션 생성까지만 하고 빠르게 반환합니다.
 * 폴링(최대 90초)과 그 결과 전달은 전부 여기서 일어나며, 결과·오류 모두 WebSocket
 * 으로만 나갑니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewFirstQuestionPoller {

    private final AiClient aiClient;
    private final AiPoller aiPoller;
    private final AiProperties aiProperties;
    private final AiErrorTranslator errorTranslator;
    private final SessionSocketHandler socketHandler;
    private final InterviewSessionWriter sessionWriter;

    /**
     * task_id 를 폴링해 첫 질문을 받아 저장하고 WebSocket 으로 밀어줍니다.
     *
     * <p>백그라운드 실행이라 여기서 던진 예외는 호출자에게 전달되지 않습니다.
     * 실패는 세션을 {@code ABORTED} 로 정리하고 {@link ErrorPushMessage} 로 프론트에
     * 알립니다.
     */
    @Async(AsyncConfig.INTERVIEW_EXECUTOR)
    public void pollAndDeliver(String sessionId, String taskId, Integer questionTotal) {
        AiTaskStatusResponse taskStatus;
        try {
            taskStatus = aiPoller.await(
                    taskId,
                    aiProperties.sessionStartTimeout(),
                    stage -> socketHandler.push(sessionId,
                            ProgressPushMessage.of(ProgressStage.from(stage))));
        } catch (BusinessException e) {
            log.warn("세션 시작 폴링 실패 sessionId={} errorCode={}", sessionId, e.getErrorCode());
            cleanupFailedSession(sessionId, e);
            pushError(sessionId, e);
            return;
        }

        // 첫 질문 자리에 결과가 없거나 session_end 가 오면 정상 질문이 아닙니다.
        // AI 계약상 첫 task 는 question/followup 을 돌려줘야 하므로 실패로 처리합니다.
        if (taskStatus.result() == null || taskStatus.result().isSessionEnd()) {
            log.warn("AI 가 첫 질문을 주지 않았습니다 sessionId={} type={}",
                    sessionId, taskStatus.result() == null ? "null" : taskStatus.result().type());
            BusinessException e = new BusinessException(
                    ErrorCode.UNEXPECTED_AI_RESPONSE, "AI 로부터 첫 질문을 받지 못했습니다");
            cleanupFailedSession(sessionId, e);
            pushError(sessionId, e);
            return;
        }

        // 저장·전달 단계의 실패도 폴링 실패와 같은 정리·통지 경로로 보냅니다.
        // 여기서 예외가 새면 @Async void 라 세션이 IN_PROGRESS 로 영구 잔류합니다.
        Question firstQuestion;
        try {
            firstQuestion = sessionWriter.saveFirstQuestion(sessionId, taskStatus.result());
        } catch (BusinessException e) {
            log.warn("첫 질문 저장 실패 sessionId={} errorCode={}", sessionId, e.getErrorCode());
            cleanupFailedSession(sessionId, e);
            pushError(sessionId, e);
            return;
        } catch (RuntimeException e) {
            log.warn("첫 질문 저장 중 예기치 못한 오류 sessionId={}", sessionId, e);
            BusinessException wrapped = new BusinessException(
                    ErrorCode.UNEXPECTED_AI_RESPONSE, "첫 질문 저장에 실패했습니다");
            wrapped.addSuppressed(e);
            cleanupFailedSession(sessionId, wrapped);
            pushError(sessionId, wrapped);
            return;
        }

        pushFirstQuestion(sessionId, firstQuestion, questionTotal);
    }

    /**
     * 폴링 실패 시 AI 세션과 우리 세션을 정리합니다.
     *
     * <p>정리 과정에서 새로 발생한 예외가 원래 폴링 예외를 덮지 않도록, 원인 예외
     * ({@code cause})에 suppressed 로 붙이고 삼킵니다. 세션 정리 실패보다 원래 실패
     * 원인을 잃지 않는 것이 중요합니다.
     */
    private void cleanupFailedSession(String sessionId, BusinessException cause) {
        try {
            aiClient.abortSession(sessionId);
        } catch (RuntimeException cleanupError) {
            cause.addSuppressed(cleanupError);
            log.warn("AI 세션 중단 실패 sessionId={}", sessionId, cleanupError);
        }
        try {
            sessionWriter.markAborted(sessionId);
        } catch (RuntimeException cleanupError) {
            cause.addSuppressed(cleanupError);
            log.warn("세션 ABORTED 처리 실패 sessionId={}", sessionId, cleanupError);
        }
    }

    private void pushFirstQuestion(String sessionId, Question question, Integer questionTotal) {
        socketHandler.push(sessionId, QuestionPushMessage.of(new QuestionPushMessage(
                question.getQuestionId(),
                question.getType().name(),
                question.getText(),
                question.getAudioUrl(),
                question.getAudioUrl() != null,
                question.getCategory(),
                question.getDifficulty(),
                question.getQuestionNumber(),
                questionTotal
        )));
    }

    private void pushError(String sessionId, BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        socketHandler.push(sessionId, ErrorPushMessage.of(new ErrorPushMessage(
                errorCode.name(),
                e.getMessage(),
                errorTranslator.isRetryable(errorCode),
                errorTranslator.needsRerecord(errorCode)
        )));
    }
}
