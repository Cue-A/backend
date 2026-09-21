package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.AiErrorTranslator;
import com.cuea.infrastructure.ai.AiPoller;
import com.cuea.infrastructure.ai.AiProperties;
import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.infrastructure.websocket.SessionSocketHandler;
import com.cuea.infrastructure.websocket.message.ErrorPushMessage;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import com.cuea.infrastructure.websocket.message.SocketMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 세션 시작 후 백그라운드 폴링 흐름을 검증합니다.
 *
 * <p>{@code @Async} 는 여기서 테스트하지 않습니다(프록시 없이 직접 호출). 폴링
 * 결과에 따른 저장·push·정리 로직만 봅니다. REST 동기 구간은
 * {@code InterviewStartServiceTest} 에서 검증합니다.
 */
class InterviewFirstQuestionPollerTest {

    private static final String SESSION_ID = "sess_1";
    private static final String TASK_ID = "task_1";

    private AiClient aiClient;
    private AiPoller aiPoller;
    private SessionSocketHandler socketHandler;
    private InterviewSessionWriter sessionWriter;
    private InterviewFirstQuestionPoller poller;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        aiPoller = mock(AiPoller.class);
        socketHandler = mock(SessionSocketHandler.class);
        sessionWriter = mock(InterviewSessionWriter.class);

        AiProperties aiProperties = new AiProperties(
                "http://localhost:8000", "secret",
                Duration.ofSeconds(90), Duration.ofSeconds(60),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10),
                new AiProperties.Mock(false));

        poller = new InterviewFirstQuestionPoller(
                aiClient, aiPoller, aiProperties, new AiErrorTranslator(), socketHandler, sessionWriter);
    }

    private AiQuestionResult firstQuestion() {
        return new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_1", null,
                "지원 동기를 말씀해 주세요.", "https://s3.../q_1.mp3",
                "지원동기", "L1", 1, 9, 0, 3, false, false, null);
    }

    @Test
    void 첫질문을_받으면_저장하고_WebSocket_으로_question_을_push_한다() {
        AiQuestionResult result = firstQuestion();
        when(aiPoller.await(eq(TASK_ID), eq(Duration.ofSeconds(90)), any()))
                .thenReturn(new AiTaskStatusResponse(AiTaskStatusResponse.STATUS_DONE, null, result, null, null));
        when(sessionWriter.saveFirstQuestion(eq(SESSION_ID), eq(result))).thenReturn(
                Question.builder().sessionId(SESSION_ID).questionId("q_1")
                        .type(QuestionType.QUESTION).text("지원 동기를 말씀해 주세요.")
                        .audioUrl("https://s3.../q_1.mp3").category("지원동기").difficulty("L1")
                        .questionNumber(1).topicIndex(0).build());
        // 수신자(WebSocket 연결) 1개가 붙어 정상 전송된 상황.
        when(socketHandler.push(eq(SESSION_ID), any())).thenReturn(1);

        poller.pollAndDeliver(SESSION_ID, TASK_ID, 9);

        verify(sessionWriter).saveFirstQuestion(SESSION_ID, result);

        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(SESSION_ID), captor.capture());
        SocketMessage<?> pushed = captor.getValue();
        assertThat(pushed.type()).isEqualTo("question");
        assertThat(pushed.payload()).isInstanceOf(QuestionPushMessage.class);
        QuestionPushMessage payload = (QuestionPushMessage) pushed.payload();
        assertThat(payload.questionId()).isEqualTo("q_1");
        assertThat(payload.questionTotal()).isEqualTo(9);
        assertThat(payload.audioAvailable()).isTrue();
    }

    @Test
    void 폴링이_실패하면_AI세션_중단_세션_ABORTED_정리_후_error_를_push_한다() {
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_TIMEOUT));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, 9);

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);

        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(SESSION_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("error");
        ErrorPushMessage payload = (ErrorPushMessage) captor.getValue().payload();
        assertThat(payload.errorCode()).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void 첫질문_없이_done_이_오면_세션을_정리하고_error_를_push_한다() {
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenReturn(new AiTaskStatusResponse(AiTaskStatusResponse.STATUS_DONE, null, null, null, null));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, 9);

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        verify(sessionWriter, org.mockito.Mockito.never()).saveFirstQuestion(anyString(), any());
    }

    @Test
    void 정리중_예외가_나도_원본_폴링_예외로_error_를_push_한다() {
        // 원본 폴링 예외: AI_TIMEOUT. cleanup(markAborted)이 실패해도 error push 는
        // 원본 코드(AI_TIMEOUT) 기준이어야 한다(정리 실패 예외로 덮이면 안 됨).
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        doThrow(new RuntimeException("DB down")).when(sessionWriter).markAborted(SESSION_ID);

        poller.pollAndDeliver(SESSION_ID, TASK_ID, 9);

        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(SESSION_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("error");
        ErrorPushMessage payload = (ErrorPushMessage) captor.getValue().payload();
        assertThat(payload.errorCode()).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void 첫결과가_session_end_이면_질문으로_저장하지_않고_세션을_정리한다() {
        // 첫 task 자리에 session_end 가 오면 계약 위반. 질문으로 저장하면 null 필드로
        // DB 오류가 나므로, UNEXPECTED_AI_RESPONSE 로 막고 세션을 정리한다.
        AiQuestionResult sessionEnd = new AiQuestionResult(
                AiQuestionResult.TYPE_SESSION_END, null, null, null, null, null, null,
                null, 9, null, null, false, false, 9);
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenReturn(new AiTaskStatusResponse(AiTaskStatusResponse.STATUS_DONE, null, sessionEnd, null, null));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, 9);

        verify(sessionWriter, org.mockito.Mockito.never()).saveFirstQuestion(anyString(), any());
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);

        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(SESSION_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("error");
        ErrorPushMessage payload = (ErrorPushMessage) captor.getValue().payload();
        assertThat(payload.errorCode()).isEqualTo("UNEXPECTED_AI_RESPONSE");
    }

    @Test
    void 첫질문_저장이_실패하면_세션을_정리하고_error_를_push_한다() {
        // saveFirstQuestion 실패도 폴링 실패와 같은 cleanup/error 경로를 타야 한다.
        // 그러지 않으면 세션이 IN_PROGRESS 로 영구 잔류한다.
        AiQuestionResult result = firstQuestion();
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenReturn(new AiTaskStatusResponse(AiTaskStatusResponse.STATUS_DONE, null, result, null, null));
        when(sessionWriter.saveFirstQuestion(eq(SESSION_ID), eq(result)))
                .thenThrow(new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "질문 타입이 아닌 AI 응답입니다"));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, 9);

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);

        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(SESSION_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("error");
        ErrorPushMessage payload = (ErrorPushMessage) captor.getValue().payload();
        assertThat(payload.errorCode()).isEqualTo("UNEXPECTED_AI_RESPONSE");
    }

    @Test
    void status_error_는_await_에서_실패로_인식되어_error_code_가_보존된다() {
        // 최신 계약의 실패 상태 error. AiPoller 가 실패로 인식해 실제 errorCode 를
        // 담은 BusinessException 을 던지면, 여기서 그 코드로 error push 해야 한다.
        // (timeout 으로 오인되지 않아야 한다.)
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다"));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, 9);

        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(SESSION_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("error");
        ErrorPushMessage payload = (ErrorPushMessage) captor.getValue().payload();
        assertThat(payload.errorCode()).isEqualTo("STT_FAILED");
        assertThat(payload.errorCode()).isNotEqualTo("AI_TIMEOUT");
        // STT 실패는 재시도 가능하고 재녹음 안내가 필요하다.
        assertThat(payload.retryable()).isTrue();
        assertThat(payload.needsRerecord()).isTrue();
    }

    @Test
    void 첫질문_push_수신자가_없어도_저장은_유지되고_흐름은_실패하지_않는다() {
        // WebSocket 핸드셰이크가 폴링보다 늦으면 push 수신자가 0개다.
        // 첫 질문은 이미 저장됐고(세션 유효), push 는 드롭되지만 세션을 ABORTED 로
        // 정리하거나 error 를 push 하지 않는다. 유실 추적용 경고 로그만 남긴다.
        // (catch-up 은 후속 이슈에서 처리)
        AiQuestionResult result = firstQuestion();
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenReturn(new AiTaskStatusResponse(AiTaskStatusResponse.STATUS_DONE, null, result, null, null));
        when(sessionWriter.saveFirstQuestion(eq(SESSION_ID), eq(result))).thenReturn(
                Question.builder().sessionId(SESSION_ID).questionId("q_1")
                        .type(QuestionType.QUESTION).text("지원 동기를 말씀해 주세요.")
                        .audioUrl("https://s3.../q_1.mp3").category("지원동기").difficulty("L1")
                        .questionNumber(1).topicIndex(0).build());
        // 수신자 없음: push 가 0 건 전송을 반환.
        when(socketHandler.push(eq(SESSION_ID), any())).thenReturn(0);

        poller.pollAndDeliver(SESSION_ID, TASK_ID, 9);

        // 질문은 저장됐고, 세션 정리(abort/markAborted)는 일어나지 않는다.
        verify(sessionWriter).saveFirstQuestion(SESSION_ID, result);
        verify(aiClient, org.mockito.Mockito.never()).abortSession(anyString());
        verify(sessionWriter, org.mockito.Mockito.never()).markAborted(anyString());

        // question push 는 시도됐다(수신자가 없었을 뿐). error push 는 없다.
        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(SESSION_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("question");
    }

    @Test
    void timeout_은_재시도_불가로_내려간다() {
        // AI_TIMEOUT 은 Backend 자체 코드다. 이름이 AI 원본 코드가 아니므로 retryable 은
        // false 여야 한다(예전엔 문자열 기준이라 항상 false 로 우연히 맞았지만, 이제는
        // ErrorCode 기준으로 명시적으로 판정한다).
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_TIMEOUT));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, 9);

        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(SESSION_ID), captor.capture());
        ErrorPushMessage payload = (ErrorPushMessage) captor.getValue().payload();
        assertThat(payload.errorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(payload.retryable()).isFalse();
        assertThat(payload.needsRerecord()).isFalse();
    }
}
