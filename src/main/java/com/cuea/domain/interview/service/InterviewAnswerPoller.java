package com.cuea.domain.interview.service;

import com.cuea.common.config.AsyncConfig;
import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.Question;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.AiErrorTranslator;
import com.cuea.infrastructure.ai.AiPoller;
import com.cuea.infrastructure.ai.AiProperties;
import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;
import com.cuea.infrastructure.websocket.SessionSocketHandler;
import com.cuea.infrastructure.websocket.message.ErrorPushMessage;
import com.cuea.infrastructure.websocket.message.ProgressPushMessage;
import com.cuea.infrastructure.websocket.message.ProgressStage;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import com.cuea.infrastructure.websocket.message.SessionEndPushMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 답변 제출 후 AI 처리 결과(다음 질문·되묻기·세션 종료)를 백그라운드에서 폴링·
 * 저장·전달합니다.
 *
 * <p><b>별도 빈으로 분리한 이유:</b> {@code @Async} 는 Spring AOP 프록시를 거쳐야
 * 동작합니다. {@link InterviewAnswerService} 안에서 self-invocation 하면 프록시를
 * 거치지 않아 동기 실행돼 버립니다. 세션 시작의
 * {@link InterviewFirstQuestionPoller} 와 같은 이유로 분리했습니다.
 *
 * <p>폴링 타임아웃은 답변 처리 기준 <b>60초</b>({@link AiProperties#answerTimeout()}),
 * 간격은 1초입니다. 결과·오류 모두 WebSocket 으로만 나갑니다.
 *
 * <p>AI 계약의 {@code result.type} 네 가지를 <b>명시적으로</b> 분기합니다.
 * <ul>
 *   <li>{@code question}·{@code followup}·{@code reask} — Question 저장 후
 *       {@code question} push. {@code reask} 는 {@code reask_of} 저장,
 *       category·difficulty 는 null 허용.</li>
 *   <li>{@code session_end} — Question 을 저장하지 않고 세션을 COMPLETED 로 전이한 뒤
 *       {@code session_end} push.</li>
 * </ul>
 * 알 수 없는 type 은 {@code question} 으로 fallback 하지 않고
 * {@code UNEXPECTED_AI_RESPONSE} 로 처리합니다.
 *
 * <h2>예외 처리 정책</h2>
 * <ul>
 *   <li><b>AI 가 알려준 실패({@link BusinessException}, {@code UNEXPECTED_AI_RESPONSE}
 *       이외)</b> — 원인 코드를 그대로 담아 {@link ErrorPushMessage} 로 알립니다.
 *       AI error_code 별 재시도·세션 정리 정책은 Issue #25 범위이므로 여기서는 세션을
 *       임의로 abort 하지 않습니다.</li>
 *   <li><b>계약 위반·예상치 못한 실패</b> — 빈 결과, 알 수 없는 type, 응답 디코딩
 *       실패, 저장 중 오류 등 Backend 가 {@code UNEXPECTED_AI_RESPONSE} 로 판정하는
 *       경우입니다. 이때는 Backend 와 AI 의 세션 상태가 동기화됐다고 보장할 수 없으므로
 *       AI 세션 abort 와 우리 세션 {@code ABORTED} 정리를 시도한 뒤 error push 합니다.
 *       cleanup 실패가 원본 원인을 덮지 않도록 suppressed·로그로만 남깁니다.</li>
 * </ul>
 * 이렇게 해야 {@code @Async void} 밖으로 예외가 유실되거나 계약 위반 응답에서 세션이
 * 프론트 통지만 받고 {@code IN_PROGRESS} 로 잔류하는 것을 막습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewAnswerPoller {

    private final AiClient aiClient;
    private final AiPoller aiPoller;
    private final AiProperties aiProperties;
    private final AiErrorTranslator errorTranslator;
    private final SessionSocketHandler socketHandler;
    private final InterviewSessionWriter sessionWriter;

    /**
     * 답변 처리 task 를 폴링해 결과 타입별로 저장·전달합니다.
     *
     * <p>백그라운드 실행이라 여기서 던진 예외는 호출자에게 전달되지 않습니다. 모든
     * 실패는 {@link ErrorPushMessage} 로 프론트에 알리며, 예상치 못한 예외는 세션까지
     * 정리합니다.
     */
    @Async(AsyncConfig.INTERVIEW_EXECUTOR)
    public void pollAndDeliver(String sessionId, String taskId) {
        AiTaskStatusResponse taskStatus;
        try {
            taskStatus = aiPoller.await(
                    taskId,
                    aiProperties.answerTimeout(),
                    stage -> socketHandler.push(sessionId,
                            ProgressPushMessage.of(ProgressStage.from(stage))));
        } catch (BusinessException e) {
            // AI 가 알려준 실패·타임아웃. error_code 별로 세션 정리 여부가 갈린다(#25).
            handlePollingFailure(sessionId, e);
            return;
        } catch (RuntimeException e) {
            // 예상치 못한 예외(예: 응답 디코딩 실패). 상태 동기화를 보장할 수 없어 정리한다.
            cleanupAndPushError(sessionId, "답변 폴링 중 예기치 못한 오류", unexpectedResponse(e));
            return;
        }

        AiQuestionResult result = taskStatus.result();
        if (result == null || result.type() == null) {
            // 계약 위반: 결과가 비어 있다. AI 상태와 어긋났을 수 있어 세션까지 정리한다.
            cleanupAndPushError(sessionId, "답변 결과가 비어 있습니다",
                    new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "AI 응답에 결과가 없습니다"));
            return;
        }

        try {
            dispatch(sessionId, result);
        } catch (BusinessException e) {
            // Backend 가 판정한 계약 위반(알 수 없는 type 등)은 상태 동기화를 보장할 수
            // 없어 세션까지 정리한다. AI 가 알려준 실패(그 외 코드)는 통지만 한다(#25).
            if (e.getErrorCode() == ErrorCode.UNEXPECTED_AI_RESPONSE) {
                cleanupAndPushError(sessionId, "답변 결과 계약 위반 type=" + result.type(), e);
            } else {
                log.warn("답변 결과 처리 실패 sessionId={} type={} errorCode={}",
                        sessionId, result.type(), e.getErrorCode());
                pushError(sessionId, e);
            }
        } catch (RuntimeException e) {
            // 저장 중 예기치 못한 예외(예: DB 오류). 상태 동기화를 보장할 수 없어 정리한다.
            cleanupAndPushError(sessionId,
                    "답변 결과 처리 중 예기치 못한 오류 type=" + result.type(), unexpectedResponse(e));
        }
    }

    /**
     * AI 폴링 실패({@link BusinessException})를 error_code 별 정책으로 처리합니다. (#25)
     *
     * <ul>
     *   <li>{@code SESSION_ENDED} — 중복 제출. 세션 정리·error push 없이 로그만 남기고 무시.</li>
     *   <li>{@code SESSION_NOT_FOUND}·{@code RESUME_PARSE_FAILED}·{@code AI_TIMEOUT}·
     *       {@code AI_UNAVAILABLE}·{@code UNEXPECTED_AI_RESPONSE} — 복구 불가/상태 불명.
     *       세션을 정리(abort+ABORTED)하고 error push.</li>
     *   <li>{@code INVALID_QUESTION_ID}·{@code INVALID_CATEGORY} — 클라이언트·조립 버그.
     *       세션을 유지하고 경고 로그 + error push.</li>
     *   <li>그 외({@code LLM_FAILED}·{@code STT_FAILED}·{@code TTS_FAILED} 등) — 재시도·
     *       재녹음 대상이라 세션을 유지하고 error push 만. (자동 재시도는 별도 확인 후.)</li>
     * </ul>
     */
    private void handlePollingFailure(String sessionId, BusinessException e) {
        ErrorCode code = e.getErrorCode();

        if (errorTranslator.isDuplicateSubmit(code)) {
            log.info("답변이 이미 종료된 세션에 도착해 무시합니다(중복 제출) sessionId={}", sessionId);
            return;
        }
        if (errorTranslator.requiresSessionAbort(code)) {
            cleanupAndPushError(sessionId, "답변 폴링 실패로 세션을 정리합니다", e);
            return;
        }
        if (errorTranslator.isClientContractError(code)) {
            log.warn("답변 폴링에서 클라이언트/조립 계약 오류 sessionId={} errorCode={}", sessionId, code);
            pushError(sessionId, e);
            return;
        }
        // 재시도·재녹음 대상(LLM/STT)·TTS 등: 세션 유지하고 통지만.
        log.warn("답변 폴링 실패(세션 유지) sessionId={} errorCode={}", sessionId, code);
        pushError(sessionId, e);
    }

    /** 예상치 못한 원인 예외를 계약 위반({@code UNEXPECTED_AI_RESPONSE})으로 감쌉니다. */
    private BusinessException unexpectedResponse(RuntimeException cause) {
        BusinessException wrapped = new BusinessException(
                ErrorCode.UNEXPECTED_AI_RESPONSE, "답변 처리에 실패했습니다");
        wrapped.addSuppressed(cause);
        return wrapped;
    }

    /**
     * 계약 위반·예상치 못한 실패 정리: AI 세션 abort 시도 → 우리 세션 ABORTED 정리
     * 시도 → error push. Backend 와 AI 상태 동기화를 보장할 수 없을 때 세션이
     * {@code IN_PROGRESS} 로 잔류하지 않도록 합니다. cleanup 예외는 원본 원인을 덮지
     * 않도록 suppressed 로 붙이고 삼킵니다.
     */
    private void cleanupAndPushError(String sessionId, String context, BusinessException error) {
        log.warn("{} sessionId={} errorCode={}", context, sessionId, error.getErrorCode());
        try {
            aiClient.abortSession(sessionId);
        } catch (RuntimeException cleanupError) {
            error.addSuppressed(cleanupError);
            log.warn("AI 세션 중단 실패 sessionId={}", sessionId, cleanupError);
        }
        try {
            sessionWriter.markAborted(sessionId);
        } catch (RuntimeException cleanupError) {
            error.addSuppressed(cleanupError);
            log.warn("세션 ABORTED 처리 실패 sessionId={}", sessionId, cleanupError);
        }
        pushError(sessionId, error);
    }

    /** 결과 타입을 명시적으로 분기합니다. 알 수 없는 type 은 질문으로 저장하지 않습니다. */
    private void dispatch(String sessionId, AiQuestionResult result) {
        String type = result.type();
        switch (type) {
            case AiQuestionResult.TYPE_QUESTION,
                 AiQuestionResult.TYPE_FOLLOWUP,
                 AiQuestionResult.TYPE_REASK -> handleQuestion(sessionId, result);
            case AiQuestionResult.TYPE_SESSION_END -> handleSessionEnd(sessionId, result);
            default -> throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE,
                    "알 수 없는 결과 타입입니다: type=" + type);
        }
    }

    /**
     * question·followup·reask 를 저장하고 push 합니다. <b>수신 즉시 저장</b>한 뒤
     * push 하므로 사용자가 중간에 나가도 기록이 남습니다.
     */
    private void handleQuestion(String sessionId, AiQuestionResult result) {
        Question question = sessionWriter.saveNextQuestion(sessionId, result);
        pushQuestion(sessionId, question, result.questionTotal());
    }

    /**
     * session_end 는 Question 을 저장하지 않고 세션을 COMPLETED 로 전이한 뒤
     * {@code session_end} 를 push 합니다. 진행률·문항 수는
     * {@code question_number/question_total} 기준이며 topic_total 은 쓰지 않습니다.
     */
    private void handleSessionEnd(String sessionId, AiQuestionResult result) {
        sessionWriter.completeSession(sessionId);
        socketHandler.push(sessionId,
                SessionEndPushMessage.of(sessionId, result.totalQuestions()));
    }

    private void pushQuestion(String sessionId, Question question, Integer questionTotal) {
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
