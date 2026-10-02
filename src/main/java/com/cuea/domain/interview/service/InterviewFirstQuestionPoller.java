package com.cuea.domain.interview.service;

import com.cuea.common.config.AsyncConfig;
import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.Question;
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

    private final AiPoller aiPoller;
    private final AiProperties aiProperties;
    private final SessionSocketHandler socketHandler;
    private final InterviewSessionWriter sessionWriter;
    private final QuestionPushFactory questionPushFactory;
    private final InterviewSessionTerminator sessionTerminator;

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
            sessionTerminator.terminateQuietly(sessionId, e);
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
            sessionTerminator.terminateQuietly(sessionId, e);
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
            sessionTerminator.terminateQuietly(sessionId, e);
            pushError(sessionId, e);
            return;
        } catch (RuntimeException e) {
            log.warn("첫 질문 저장 중 예기치 못한 오류 sessionId={}", sessionId, e);
            BusinessException wrapped = new BusinessException(
                    ErrorCode.UNEXPECTED_AI_RESPONSE, "첫 질문 저장에 실패했습니다");
            wrapped.addSuppressed(e);
            sessionTerminator.terminateQuietly(sessionId, wrapped);
            pushError(sessionId, wrapped);
            return;
        }

        pushFirstQuestion(sessionId, firstQuestion, questionTotal);
    }

    private void pushFirstQuestion(String sessionId, Question question, Integer questionTotal) {
        // 사용자 abort 등으로 세션이 이미 종료됐으면 saveFirstQuestion 이 저장하지 않고
        // null 을 돌려줍니다(#25). 그 경우 첫 질문 push 도 하지 않고 조용히 무시합니다.
        // (세션은 사용자 abort 로 이미 ABORTED 라 여기서 추가 정리·error push 는 하지 않습니다.)
        if (question == null) {
            log.info("이미 종료된 세션에 늦게 도착한 첫 질문이라 저장·push 하지 않습니다 sessionId={}", sessionId);
            return;
        }
        int delivered = socketHandler.push(sessionId,
                QuestionPushMessage.of(questionPushFactory.create(question, questionTotal)));
        // WebSocket 핸드셰이크가 폴링보다 늦으면 수신자가 없어 첫 질문 push 가 드롭됩니다.
        // 질문 자체는 DB 에 저장돼 있으므로 세션은 유효하지만, 프론트는 첫 질문을 못 받습니다.
        // pending 버퍼/catch-up 은 후속 작업으로 분리했고, 여기서는 유실을 추적할 수 있게
        // 경고 로그만 남깁니다.
        if (delivered == 0) {
            log.warn("첫 질문 push 수신자 없음(유실) sessionId={} questionId={}. "
                            + "WebSocket 미연결로 첫 질문이 드롭됐습니다(질문은 DB 저장됨). "
                            + "catch-up 은 후속 이슈에서 처리.",
                    sessionId, question.getQuestionId());
        }
    }

    private void pushError(String sessionId, BusinessException e) {
        // Backend 는 startSession 을 재전송하지 않아 자동 재시도 여지가 없다(retryable=false).
        socketHandler.push(sessionId,
                ErrorPushMessage.finalFailure(e.getErrorCode(), e.getMessage()));
    }
}
