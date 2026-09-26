package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.AiErrorTranslator;
import com.cuea.infrastructure.ai.AiPoller;
import com.cuea.infrastructure.ai.AiProperties;
import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiAnswerSubmitRequest;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /** 재전송 대상이 되는 최초 답변 요청. 재시도 시 이 객체가 그대로 다시 제출돼야 한다. */
    private static final AiAnswerSubmitRequest REQUEST = new AiAnswerSubmitRequest(
            "q_1", "https://s3/get/audio", null, false);

    private AiClient aiClient;
    private AiPoller aiPoller;
    private AiProperties aiProperties;
    private SessionSocketHandler socketHandler;
    private InterviewSessionWriter sessionWriter;
    private InterviewAnswerPoller poller;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        aiPoller = mock(AiPoller.class);
        socketHandler = mock(SessionSocketHandler.class);
        sessionWriter = mock(InterviewSessionWriter.class);

        aiProperties = new AiProperties(
                "http://localhost:8000", "secret",
                Duration.ofSeconds(90), Duration.ofSeconds(60),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10),
                new AiProperties.Mock(false));

        poller = new InterviewAnswerPoller(
                aiClient, aiPoller, aiProperties, new AiErrorTranslator(), socketHandler, sessionWriter);
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

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

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

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

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

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

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

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

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

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

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
    void 알_수_없는_result_type_은_Question_으로_저장하지_않고_세션을_정리하고_error_를_push_한다() {
        AiQuestionResult unknown = new AiQuestionResult(
                "something_new", "q_x", null, "?", null, null, null,
                null, 9, null, null, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(unknown));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(sessionWriter, never()).saveNextQuestion(anyString(), any());
        verify(sessionWriter, never()).completeSession(anyString());
        // Backend 판정 계약 위반(UNEXPECTED_AI_RESPONSE)은 상태 동기화 보장 불가라 세션 정리.
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("UNEXPECTED_AI_RESPONSE");
    }

    @Test
    void 결과가_비어_있으면_세션을_정리하고_error_를_push_한다() {
        // done 인데 result 가 null. 계약 위반이라 세션까지 정리한다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenReturn(new AiTaskStatusResponse(AiTaskStatusResponse.STATUS_DONE, null, null, null, null));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(sessionWriter, never()).saveNextQuestion(anyString(), any());
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
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

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        QuestionPushMessage payload = capturePush("question", QuestionPushMessage.class);
        assertThat(payload.questionTotal()).isEqualTo(9);
        // QuestionPushMessage 에는 topic_total 필드 자체가 없다(진행률에 쓰지 않으므로).
        boolean hasTopicTotal = java.util.Arrays.stream(QuestionPushMessage.class.getRecordComponents())
                .anyMatch(c -> c.getName().toLowerCase().contains("topic"));
        assertThat(hasTopicTotal).isFalse();
    }

    // ── STT/LLM 1회 자동 재시도 (Issue #25, Option 1) ────────────

    private static final String RETRY_TASK_ID = "task_ans_2";

    @Test
    void STT_FAILED_1차_실패하면_같은_요청을_1회_재전송하고_성공하면_정상_진행한다() {
        // 1차 폴링은 STT_FAILED, 재전송으로 받은 새 task 는 정상 done.
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_2", null, "다음 질문", "https://s3/q.mp3",
                "직무역량", "L2", 2, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다"));
        when(aiClient.submitAnswer(eq(SESSION_ID), eq(REQUEST))).thenReturn(RETRY_TASK_ID);
        when(aiPoller.await(eq(RETRY_TASK_ID), any(), any())).thenReturn(done(result));
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result)))
                .thenReturn(savedQuestion("q_2", QuestionType.QUESTION, "직무역량", "L2", 2));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        // 같은 request 를 정확히 1회 재전송했다.
        verify(aiClient, times(1)).submitAnswer(SESSION_ID, REQUEST);
        // 새 taskId 를 폴링했다.
        verify(aiPoller).await(eq(RETRY_TASK_ID), any(), any());
        // 정상 질문 저장·push, 세션 정리 없음.
        verify(sessionWriter).saveNextQuestion(SESSION_ID, result);
        verify(aiClient, never()).abortSession(anyString());
        verify(sessionWriter, never()).markAborted(anyString());
        QuestionPushMessage payload = capturePush("question", QuestionPushMessage.class);
        assertThat(payload.questionId()).isEqualTo("q_2");
    }

    @Test
    void STT_FAILED_가_2회_연속이면_재전송은_1회만_하고_needsRerecord_로_통지한다() {
        // 1차·재시도 모두 STT_FAILED. 재전송은 딱 1회, 이후 재녹음 안내.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다"));
        when(aiClient.submitAnswer(eq(SESSION_ID), eq(REQUEST))).thenReturn(RETRY_TASK_ID);
        when(aiPoller.await(eq(RETRY_TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다"));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        // 재전송은 정확히 1회 (MAX_RETRY=1).
        verify(aiClient, times(1)).submitAnswer(SESSION_ID, REQUEST);
        // 최종 STT_FAILED 는 재녹음 안내, 세션 유지, abort 없음.
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("STT_FAILED");
        assertThat(payload.needsRerecord()).isTrue();
        verify(sessionWriter, never()).saveNextQuestion(anyString(), any());
        verify(aiClient, never()).abortSession(anyString());
        verify(sessionWriter, never()).markAborted(anyString());
    }

    @Test
    void LLM_FAILED_1차_실패하면_같은_요청을_재전송하고_성공하면_정상_진행한다() {
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_2", null, "다음 질문", "https://s3/q.mp3",
                "직무역량", "L2", 2, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.LLM_FAILED, "질문 생성 실패"));
        when(aiClient.submitAnswer(eq(SESSION_ID), eq(REQUEST))).thenReturn(RETRY_TASK_ID);
        when(aiPoller.await(eq(RETRY_TASK_ID), any(), any())).thenReturn(done(result));
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result)))
                .thenReturn(savedQuestion("q_2", QuestionType.QUESTION, "직무역량", "L2", 2));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        // 동일 request 를 재전송하고 새 taskId 로 폴링해 정상 진행.
        verify(aiClient, times(1)).submitAnswer(SESSION_ID, REQUEST);
        verify(aiPoller).await(eq(RETRY_TASK_ID), any(), any());
        verify(sessionWriter).saveNextQuestion(SESSION_ID, result);
        verify(aiClient, never()).abortSession(anyString());
        QuestionPushMessage payload = capturePush("question", QuestionPushMessage.class);
        assertThat(payload.questionId()).isEqualTo("q_2");
    }

    // ── T1: 재전송 POST 자체가 실패 (최신 오류 정책 적용) ────────

    @Test
    void 재전송_POST가_AI_UNAVAILABLE이면_원래_STT로_가리지_않고_최신_코드로_세션을_정리한다() {
        // 최초 STT_FAILED → 재전송하려는 submitAnswer 가 AI_UNAVAILABLE 로 실패.
        // 원래 STT 로 가리지 않고 최신 AI_UNAVAILABLE 정책(기존 cleanup)으로 ABORTED.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다"));
        when(aiClient.submitAnswer(eq(SESSION_ID), eq(REQUEST)))
                .thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        // 재전송은 정확히 1회 시도됐다(그리고 오류).
        verify(aiClient, times(1)).submitAnswer(SESSION_ID, REQUEST);
        // 새 task 폴링은 없다(재전송 POST 가 오류).
        verify(aiPoller, never()).await(eq(RETRY_TASK_ID), any(), any());
        // 최신 AI_UNAVAILABLE 기준 cleanup.
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    void 재전송_POST가_INVALID_QUESTION_ID면_원래_LLM으로_가리지_않고_세션을_정리한다() {
        // 최초 LLM_FAILED → 재전송 POST 가 INVALID_QUESTION_ID(이미 다음 질문으로 진행됨).
        // 재시도 이후 INVALID_QUESTION_ID 는 세션 정리(AI 확정 정책).
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.LLM_FAILED, "질문 생성 실패"));
        when(aiClient.submitAnswer(eq(SESSION_ID), eq(REQUEST)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_QUESTION_ID));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient, times(1)).submitAnswer(SESSION_ID, REQUEST);
        verify(aiPoller, never()).await(eq(RETRY_TASK_ID), any(), any());
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("INVALID_QUESTION_ID");
    }

    // ── T2: 재전송 성공 후 새 task 가 다른 error_code 로 실패 ──

    @Test
    void STT_재전송_성공_후_새_task가_SESSION_NOT_FOUND면_최신_코드로_세션을_정리한다() {
        // 최초 STT_FAILED → 재전송 성공 → 새 task 가 SESSION_NOT_FOUND.
        // 최종 정책은 최초 STT 가 아니라 최신 SESSION_NOT_FOUND 기준이어야 한다:
        // cleanup(abort + ABORTED). 최초 STT 의 needsRerecord 정책은 적용되지 않는다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다"));
        when(aiClient.submitAnswer(eq(SESSION_ID), eq(REQUEST))).thenReturn(RETRY_TASK_ID);
        when(aiPoller.await(eq(RETRY_TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        // 재전송 1회, 새 task 폴링 1회.
        verify(aiClient, times(1)).submitAnswer(SESSION_ID, REQUEST);
        verify(aiPoller).await(eq(RETRY_TASK_ID), any(), any());
        // 최신 코드(SESSION_NOT_FOUND) 기준 cleanup.
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("SESSION_NOT_FOUND");
        // 최초 STT 의 needsRerecord 정책은 적용되지 않는다.
        assertThat(payload.needsRerecord()).isFalse();
    }

    @Test
    void LLM_재전송_성공_후_새_task가_AI_TIMEOUT이면_최신_코드로_세션을_정리한다() {
        // 최초 LLM_FAILED → 재전송 성공 → 새 task 가 AI_TIMEOUT → AI_TIMEOUT 기준 cleanup.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.LLM_FAILED, "질문 생성 실패"));
        when(aiClient.submitAnswer(eq(SESSION_ID), eq(REQUEST))).thenReturn(RETRY_TASK_ID);
        when(aiPoller.await(eq(RETRY_TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_TIMEOUT));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient, times(1)).submitAnswer(SESSION_ID, REQUEST);
        verify(aiPoller).await(eq(RETRY_TASK_ID), any(), any());
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void STT_재전송_성공_후_새_task가_INVALID_QUESTION_ID면_세션을_정리한다() {
        // 최초 STT_FAILED → 재전송 성공 → 새 task 가 INVALID_QUESTION_ID(이미 다음 질문 진행).
        // 재시도 이후 INVALID_QUESTION_ID 는 세션 정리(AI 확정 정책). 최초 INVALID_QUESTION_ID
        // (재시도 없음)의 통지-only 정책과 구분된다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다"));
        when(aiClient.submitAnswer(eq(SESSION_ID), eq(REQUEST))).thenReturn(RETRY_TASK_ID);
        when(aiPoller.await(eq(RETRY_TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_QUESTION_ID));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient, times(1)).submitAnswer(SESSION_ID, REQUEST);
        verify(aiPoller).await(eq(RETRY_TASK_ID), any(), any());
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("INVALID_QUESTION_ID");
    }

    @Test
    void 재전송_후_두번째도_LLM_FAILED면_재전송은_1회만_하고_세션을_정리한다() {
        // 최초 LLM_FAILED → 재전송 → 새 task 도 LLM_FAILED. 재전송은 1회만(MAX_RETRY=1),
        // 재시도 이후 LLM_FAILED 는 세션 정리(AI 확정 정책). IN_PROGRESS 유지하면 안 된다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.LLM_FAILED, "질문 생성 실패"));
        when(aiClient.submitAnswer(eq(SESSION_ID), eq(REQUEST))).thenReturn(RETRY_TASK_ID);
        when(aiPoller.await(eq(RETRY_TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.LLM_FAILED, "질문 생성 실패"));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        // 재전송은 딱 1회. 세 번째 POST 없음.
        verify(aiClient, times(1)).submitAnswer(SESSION_ID, REQUEST);
        // 재시도 이후 LLM_FAILED 는 세션 정리.
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("LLM_FAILED");
    }

    @Test
    void non_retryable_error_는_재전송하지_않고_기존_처리를_따른다() {
        // AI_TIMEOUT 은 재시도 대상이 아니다. submitAnswer 재호출 없이 기존 cleanup 을 탄다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_TIMEOUT));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        // 재전송 없음.
        verify(aiClient, never()).submitAnswer(anyString(), any());
        // AI_TIMEOUT 은 기존 정책대로 세션 정리.
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void processing_동안에는_재전송하지_않는다() {
        // AiPoller.await 가 done 을 돌려줄 때까지(내부적으로 processing 을 폴링) 정상 진행하며,
        // 재전송(submitAnswer)은 한 번도 일어나지 않는다. status=error 를 실제로 받은 뒤에만
        // 재전송한다는 계약을 폴러 관점에서 확인한다.
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_2", null, "다음 질문", "https://s3/q.mp3",
                "직무역량", "L2", 2, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(result));
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result)))
                .thenReturn(savedQuestion("q_2", QuestionType.QUESTION, "직무역량", "L2", 2));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        // 정상 done 이면 재전송 없음.
        verify(aiClient, never()).submitAnswer(anyString(), any());
        verify(sessionWriter).saveNextQuestion(SESSION_ID, result);
    }

    // ── error_code 별 cleanup 정책 (Issue #25) ───────────────────

    @Test
    void SESSION_NOT_FOUND_은_세션을_정리하고_error_를_push_한다() {
        // AI 쪽 세션이 사라짐(재배포 등). 복구 불가라 세션을 ABORTED 로 정리한다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void AI_TIMEOUT_은_답변_흐름에서도_세션을_정리한다() {
        // 답변 폴링 타임아웃도 세션 시작과 동일하게 세션을 정리해야 일관적이다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_TIMEOUT));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void AI_UNAVAILABLE_도_세션을_정리한다() {
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    void SESSION_ENDED_는_중복_제출로_무시한다_abort도_error_push도_없다() {
        // 이미 종료된 세션에 답변이 또 들어온 상황. 계약대로 무시(로그만).
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.SESSION_ENDED));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient, never()).abortSession(anyString());
        verify(sessionWriter, never()).markAborted(anyString());
        // error push 도 하지 않는다.
        verify(socketHandler, never()).push(eq(SESSION_ID), any());
    }

    @Test
    void INVALID_QUESTION_ID_는_abort_없이_error_만_push_한다() {
        // 클라이언트 버그. 세션을 정리하지 않고 오류만 전달한다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_QUESTION_ID));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient, never()).abortSession(anyString());
        verify(sessionWriter, never()).markAborted(anyString());
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("INVALID_QUESTION_ID");
    }

    @Test
    void INVALID_CATEGORY_는_abort_없이_error_만_push_한다() {
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_CATEGORY));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient, never()).abortSession(anyString());
        verify(sessionWriter, never()).markAborted(anyString());
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("INVALID_CATEGORY");
    }

    // ── C1: 예상치 못한(non-Business) 예외 ──────────────────────

    @Test
    void await_에서_예상치_못한_RuntimeException_이_나면_세션을_정리하고_UNEXPECTED_AI_RESPONSE_를_push_한다() {
        // 예: 응답 디코딩 실패 등 BusinessException 이 아닌 예외. Backend/AI 상태 동기화를
        // 보장할 수 없으므로 AI abort + 세션 ABORTED 정리 + error push 해야 한다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new IllegalStateException("decode failed"));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("UNEXPECTED_AI_RESPONSE");
    }

    @Test
    void 저장_중_예상치_못한_RuntimeException_이_나도_세션을_정리하고_error_를_push_한다() {
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_2", null, "다음 질문", "https://s3/q.mp3",
                "직무역량", "L2", 2, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(result));
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result)))
                .thenThrow(new RuntimeException("DB down"));

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("UNEXPECTED_AI_RESPONSE");
    }

    @Test
    void 정리중_예외가_나도_원본_실패로_error_를_push_한다() {
        // cleanup(markAborted)이 실패해도 error push 는 나가야 하고, 예외가 @Async 밖으로
        // 새지 않아야 한다.
        when(aiPoller.await(eq(TASK_ID), any(), any()))
                .thenThrow(new IllegalStateException("decode failed"));
        doThrow(new RuntimeException("cleanup down")).when(sessionWriter).markAborted(SESSION_ID);

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        ErrorPushMessage payload = capturePush("error", ErrorPushMessage.class);
        assertThat(payload.errorCode()).isEqualTo("UNEXPECTED_AI_RESPONSE");
    }

    // ── TTS text-only 정상 경로 회귀 (Issue #25) ─────────────────

    @Test
    void TTS_실패로_audio_url_이_null_이어도_질문을_저장하고_텍스트로_push_한다() {
        // 계약: status=done + result.text 있음 + audio_url=null (TTS_FAILED 시 음성만 없음).
        // 이 경우는 오류가 아니라 정상 진행이다. 질문을 저장하고 audioAvailable=false 로
        // push 하며, error push 나 세션 정리를 하지 않는다.
        // (새 TTS_FAILED error 처리 로직을 추가하지 않는다 — 기존 handleQuestion 경로 그대로.)
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_2", null, "다음 질문 텍스트", null,
                "직무역량", "L2", 2, 9, 1, 4, false, false, null);
        when(aiPoller.await(eq(TASK_ID), any(), any())).thenReturn(done(result));
        // 저장된 Question 도 audioUrl=null 이다(TTS 없음).
        Question savedWithoutAudio = Question.builder()
                .sessionId(SESSION_ID).questionId("q_2").type(QuestionType.QUESTION)
                .text("다음 질문 텍스트").audioUrl(null)
                .category("직무역량").difficulty("L2")
                .questionNumber(2).topicIndex(1).build();
        when(sessionWriter.saveNextQuestion(eq(SESSION_ID), eq(result))).thenReturn(savedWithoutAudio);

        poller.pollAndDeliver(SESSION_ID, TASK_ID, REQUEST);

        // 질문은 정상 저장된다.
        verify(sessionWriter).saveNextQuestion(SESSION_ID, result);
        // 세션은 유지된다(정리하지 않음).
        verify(aiClient, never()).abortSession(anyString());
        verify(sessionWriter, never()).markAborted(anyString());
        verify(sessionWriter, never()).completeSession(anyString());

        // question 으로 push 하되 audio_url 은 null, audioAvailable=false.
        QuestionPushMessage payload = capturePush("question", QuestionPushMessage.class);
        assertThat(payload.questionId()).isEqualTo("q_2");
        assertThat(payload.text()).isEqualTo("다음 질문 텍스트");
        assertThat(payload.audioUrl()).isNull();
        assertThat(payload.audioAvailable()).isFalse();
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
