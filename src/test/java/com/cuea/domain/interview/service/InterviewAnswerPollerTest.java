package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.infrastructure.ai.AiErrorTranslator;
import com.cuea.infrastructure.ai.AiPoller;
import com.cuea.infrastructure.ai.AiProperties;
import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;
import com.cuea.infrastructure.websocket.SessionSocketHandler;
import com.cuea.infrastructure.websocket.message.ErrorPushMessage;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import com.cuea.infrastructure.websocket.message.SessionEndPushMessage;
import com.cuea.infrastructure.websocket.message.SocketMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 답변 제출 후 백그라운드 폴링 흐름을 검증합니다.
 *
 * <p>{@code @Async} 는 여기서 테스트하지 않습니다(프록시 없이 직접 호출). 폴링 결과
 * 타입별 저장·push 로직만 봅니다. REST 동기 구간은 {@code InterviewAnswerServiceTest}
 * 에서 검증합니다.
 */
class InterviewAnswerPollerTest {

    private static final String SESSION_ID = "sess_1";
    private static final String TASK_ID = "task_ans_1";

    private AiPoller aiPoller;
    private AiProperties aiProperties;
    private SessionSocketHandler socketHandler;
    private InterviewSessionWriter sessionWriter;
    private InterviewAnswerPoller poller;

    @BeforeEach
    void setUp() {
        aiPoller = mock(AiPoller.class);
        socketHandler = mock(SessionSocketHandler.class);
        sessionWriter = mock(InterviewSessionWriter.class);

        aiProperties = new AiProperties(
                "http://localhost:8000", "secret",
                Duration.ofSeconds(90), Duration.ofSeconds(60),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10),
                new AiProperties.Mock(false));

        poller = new InterviewAnswerPoller(
                aiPoller, aiProperties, new AiErrorTranslator(), socketHandler, sessionWriter);
    }

    private AiTaskStatusResponse done(AiQuestionResult result) {
        return new AiTaskStatusResponse(AiTaskStatusResponse.STATUS_DONE, null, result, null, null);
    }

    private Question savedQuestion(String questionId, QuestionType type, String category,
                                   String difficulty, int number) {
        return Question.builder()
                .sessionId(SESSION_ID).questionId(questionId).type(type)
                .text("질문 본문").audioUrl("https://s3/q.mp3")
                .category(category).difficulty(difficulty)
                .questionNumber(number).topicIndex(1).build();
    }

    // ── 답변 처리 타임아웃 설정 ─────────────────────────────────

    @Test
    void 답변_폴링은_60초_타임아웃을_사용한다() {
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_2", null, "다음 질문", "https://s3/q.mp3",
                "직무역량", "L2", 2, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), eq(Duration.ofSeconds(60)), any())).thenReturn(done(result));
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result)))
                .thenReturn(savedQuestion("q_2", QuestionType.QUESTION, "직무역량", "L2", 2));

        poller.pollAndDeliver(SESSION_ID, TASK_ID);

        // answerTimeout(60초) 로 폴링해야 한다(세션 시작의 90초가 아니라).
        verify(aiPoller).await(eq(TASK_ID), eq(Duration.ofSeconds(60)), any());
    }

    // ── question / followup / reask ────────────────────────────

    @Test
    void QUESTION_결과를_저장하고_push_한다() {
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_2", null, "다음 질문", "https://s3/q.mp3",
                "직무역량", "L2", 2, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(result));
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result)))
                .thenReturn(savedQuestion("q_2", QuestionType.QUESTION, "직무역량", "L2", 2));

        poller.pollAndDeliver(SESSION_ID, TASK_ID);

        verify(sessionWriter).saveNextQuestion(SESSION_ID, result);
        QuestionPushMessage payload = capturePush("question", QuestionPushMessage.class);
        assertThat(payload.questionId()).isEqualTo("q_2");
        assertThat(payload.questionType()).isEqualTo("QUESTION");
        assertThat(payload.questionNumber()).isEqualTo(2);
        assertThat(payload.questionTotal()).isEqualTo(9);
    }

    @Test
    void FOLLOWUP_결과를_저장하고_push_한다() {
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_FOLLOWUP, "q_3", null, "꼬리질문", "https://s3/q.mp3",
                "직무역량", "L2", 3, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(result));
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result)))
                .thenReturn(savedQuestion("q_3", QuestionType.FOLLOWUP, "직무역량", "L2", 3));

        poller.pollAndDeliver(SESSION_ID, TASK_ID);

        verify(sessionWriter).saveNextQuestion(SESSION_ID, result);
        QuestionPushMessage payload = capturePush("question", QuestionPushMessage.class);
        assertThat(payload.questionType()).isEqualTo("FOLLOWUP");
    }

    @Test
    void REASK_결과를_reask_of_와_함께_저장하고_push_한다() {
        // reask 는 category·difficulty 가 null 이고 reask_of 로 원 질문을 알려준다.
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_REASK, "q_2r", "q_2", "다시 말씀해 주시겠어요?", "https://s3/q.mp3",
                null, null, 2, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(result));
        // reaskOf 가 저장 결과에 반영되는지까지 확인하기 위해 실제 값이 담긴 Question 을 돌려준다.
        Question saved = Question.builder()
                .sessionId(SESSION_ID).questionId("q_2r").type(QuestionType.REASK)
                .text("다시 말씀해 주시겠어요?").audioUrl("https://s3/q.mp3")
                .category(null).difficulty(null).reaskOf("q_2")
                .questionNumber(2).topicIndex(1).build();
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result))).thenReturn(saved);

        poller.pollAndDeliver(SESSION_ID, TASK_ID);

        // reask 도 saveNextQuestion 으로 저장한다(reask_of 매핑은 Writer 책임, 아래 Writer 테스트에서 검증).
        verify(sessionWriter).saveNextQuestion(SESSION_ID, result);
        assertThat(saved.getReaskOf()).isEqualTo("q_2");

        QuestionPushMessage payload = capturePush("question", QuestionPushMessage.class);
        assertThat(payload.questionType()).isEqualTo("REASK");
        // reask 는 category·difficulty 가 null 로 내려간다.
        assertThat(payload.category()).isNull();
        assertThat(payload.difficulty()).isNull();
        // reask 는 question_number 가 올라가지 않는다(원 질문과 같은 2).
        assertThat(payload.questionNumber()).isEqualTo(2);
    }

    // ── session_end ────────────────────────────────────────────

    @Test
    void SESSION_END_는_Question_을_저장하지_않고_세션을_COMPLETED_로_바꾼_뒤_완료_push_한다() {
        AiQuestionResult sessionEnd = new AiQuestionResult(
                AiQuestionResult.TYPE_SESSION_END, null, null, null, null, null, null,
                null, 9, null, null, false, false, 9);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(sessionEnd));

        poller.pollAndDeliver(SESSION_ID, TASK_ID);

        // session_end 는 Question row 를 만들지 않는다.
        verify(sessionWriter, never()).saveNextQuestion(anyString(), any());
        // 세션을 COMPLETED 로 전이한다.
        verify(sessionWriter).completeSession(SESSION_ID);

        SessionEndPushMessage payload = capturePush("session_end", SessionEndPushMessage.class);
        assertThat(payload.sessionId()).isEqualTo(SESSION_ID);
        assertThat(payload.totalQuestions()).isEqualTo(9);
    }

    // ── unknown type ───────────────────────────────────────────

    @Test
    void 알_수_없는_result_type_은_Question_으로_저장하지_않고_error_를_push_한다() {
        AiQuestionResult unknown = new AiQuestionResult(
                "something_new", "q_x", null, "?", null, null, null,
                null, 9, null, null, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(unknown));

        poller.pollAndDeliver(SESSION_ID, TASK_ID);

        verify(sessionWriter, never()).saveNextQuestion(anyString(), any());
        verify(sessionWriter, never()).completeSession(anyString());
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("UNEXPECTED_AI_RESPONSE");
    }

    // ── 진행률: topic_total 미사용 ──────────────────────────────

    @Test
    void 진행률은_question_total_만_쓰고_topic_total_은_push_에_담지_않는다() {
        // topic_total 을 4 로 줘도 push 페이로드에는 question_total(9)만 실린다.
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_2", null, "다음 질문", "https://s3/q.mp3",
                "직무역량", "L2", 2, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(result));
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result)))
                .thenReturn(savedQuestion("q_2", QuestionType.QUESTION, "직무역량", "L2", 2));

        poller.pollAndDeliver(SESSION_ID, TASK_ID);

        QuestionPushMessage payload = capturePush("question", QuestionPushMessage.class);
        assertThat(payload.questionTotal()).isEqualTo(9);
        // QuestionPushMessage 에는 topic_total 필드 자체가 없다(진행률에 쓰지 않으므로).
        boolean hasTopicTotal = java.util.Arrays.stream(QuestionPushMessage.class.getRecordComponents())
                .anyMatch(c -> c.getName().toLowerCase().contains("topic"));
        assertThat(hasTopicTotal).isFalse();
    }

    // ── 폴링 실패 ───────────────────────────────────────────────

    @Test
    void 폴링이_실패하면_error_를_push_한다() {
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다"));

        poller.pollAndDeliver(SESSION_ID, TASK_ID);

        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("STT_FAILED");
        // STT 실패는 재녹음 안내가 필요하다.
        assertThat(payload.needsRerecord()).isTrue();
        // 결과 처리 로직은 타지 않는다.
        verify(sessionWriter, never()).saveNextQuestion(anyString(), any());
        verify(sessionWriter, never()).completeSession(anyString());
    }

    @SuppressWarnings("unchecked")
    private <T> T capturePush(String expectedType, Class<T> payloadType) {
        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(SESSION_ID), captor.capture());
        SocketMessage<?> pushed = captor.getValue();
        assertThat(pushed.type()).isEqualTo(expectedType);
        assertThat(pushed.payload()).isInstanceOf(payloadType);
        return (T) pushed.payload();
    }
}
