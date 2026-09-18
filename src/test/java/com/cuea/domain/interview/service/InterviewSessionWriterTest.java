package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 답변 흐름에서 {@link InterviewSessionWriter} 가 하는 짧은 트랜잭션 DB 작업을
 * 검증합니다. 결과 타입 매핑(특히 {@code reask_of})과 session_end 전이,
 * 알 수 없는 type 거부를 봅니다.
 */
class InterviewSessionWriterTest {

    private static final String SESSION_ID = "sess_1";

    private InterviewSessionRepository sessionRepository;
    private QuestionRepository questionRepository;
    private InterviewSessionWriter writer;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        questionRepository = mock(QuestionRepository.class);
        writer = new InterviewSessionWriter(sessionRepository, questionRepository);
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AiQuestionResult reaskResult() {
        return new AiQuestionResult(
                AiQuestionResult.TYPE_REASK, "q_2r", "q_2", "다시 말씀해 주시겠어요?", "https://s3/q.mp3",
                null, null, 2, 9, 1, 4, false, false, null);
    }

    @Test
    void REASK_를_저장하면_reask_of_와_null_category_difficulty_가_반영된다() {
        Question saved = writer.saveNextQuestion(SESSION_ID, reaskResult());

        assertThat(saved.getType()).isEqualTo(QuestionType.REASK);
        assertThat(saved.getReaskOf()).isEqualTo("q_2");
        assertThat(saved.getCategory()).isNull();
        assertThat(saved.getDifficulty()).isNull();
        // reask 는 question_number 가 올라가지 않는다(원 질문과 같은 2).
        assertThat(saved.getQuestionNumber()).isEqualTo(2);
    }

    @Test
    void QUESTION_을_저장하면_카테고리_난이도가_그대로_보존된다() {
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_2", null, "다음 질문", "https://s3/q.mp3",
                "협업·갈등", "L3", 2, 9, 1, 4, false, false, null);

        Question saved = writer.saveNextQuestion(SESSION_ID, result);

        assertThat(saved.getType()).isEqualTo(QuestionType.QUESTION);
        // 가운뎃점(·)까지 그대로 저장.
        assertThat(saved.getCategory()).isEqualTo("협업·갈등");
        assertThat(saved.getDifficulty()).isEqualTo("L3");
        assertThat(saved.getReaskOf()).isNull();
    }

    @Test
    void session_end_는_질문으로_저장하지_않고_거부한다() {
        AiQuestionResult sessionEnd = new AiQuestionResult(
                AiQuestionResult.TYPE_SESSION_END, null, null, null, null, null, null,
                null, 9, null, null, false, false, 9);

        assertThatThrownBy(() -> writer.saveNextQuestion(SESSION_ID, sessionEnd))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNEXPECTED_AI_RESPONSE);

        verify(questionRepository, never()).save(any());
    }

    @Test
    void 알_수_없는_type_은_QUESTION_으로_fallback_하지_않고_거부한다() {
        // C10: unknown → QUESTION fallback 회귀 방지. Writer 레벨에서도 직접 검증한다.
        AiQuestionResult unknown = new AiQuestionResult(
                "something_new", "q_x", null, "?", null, null, null,
                null, 9, null, null, false, false, null);

        assertThatThrownBy(() -> writer.saveNextQuestion(SESSION_ID, unknown))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNEXPECTED_AI_RESPONSE);

        verify(questionRepository, never()).save(any());
    }

    @Test
    void attachAnswerMedia_는_object_key_와_timeout_을_질문에_저장한다() {
        Question question = Question.builder()
                .sessionId(SESSION_ID).questionId("q_1").type(QuestionType.QUESTION)
                .text("질문").questionNumber(1).topicIndex(0).build();
        when(questionRepository.findBySessionIdAndQuestionId(SESSION_ID, "q_1"))
                .thenReturn(Optional.of(question));

        writer.attachAnswerMedia(SESSION_ID, "q_1",
                "sessions/sess_1/answers/q_1.webm", "sessions/sess_1/answers/q_1_video.mp4", true);

        assertThat(question.getAnswerAudioObjectKey()).isEqualTo("sessions/sess_1/answers/q_1.webm");
        assertThat(question.getAnswerVideoObjectKey()).isEqualTo("sessions/sess_1/answers/q_1_video.mp4");
        assertThat(question.isAnswerIsTimeout()).isTrue();
    }

    @Test
    void attachAnswerMedia_는_video_가_null_이어도_저장한다() {
        Question question = Question.builder()
                .sessionId(SESSION_ID).questionId("q_1").type(QuestionType.QUESTION)
                .text("질문").questionNumber(1).topicIndex(0).build();
        when(questionRepository.findBySessionIdAndQuestionId(SESSION_ID, "q_1"))
                .thenReturn(Optional.of(question));

        writer.attachAnswerMedia(SESSION_ID, "q_1", "sessions/sess_1/answers/q_1.webm", null, false);

        assertThat(question.getAnswerAudioObjectKey()).isEqualTo("sessions/sess_1/answers/q_1.webm");
        assertThat(question.getAnswerVideoObjectKey()).isNull();
        assertThat(question.isAnswerIsTimeout()).isFalse();
    }

    @Test
    void attachAnswerMedia_대상_질문이_없으면_QUESTION_NOT_FOUND() {
        when(questionRepository.findBySessionIdAndQuestionId(SESSION_ID, "q_x"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.attachAnswerMedia(SESSION_ID, "q_x", "key", null, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.QUESTION_NOT_FOUND);
    }

    @Test
    void completeSession_은_세션을_COMPLETED_로_전이한다() {
        InterviewSession session = InterviewSession.builder()
                .sessionId(SESSION_ID).user(mock(User.class))
                .document(mock(com.cuea.domain.document.entity.Document.class))
                .mode("PRACTICE").jobRole("백엔드").questionCount(9)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.IN_PROGRESS).build();
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.of(session));

        writer.completeSession(SESSION_ID);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getCompletedAt()).isNotNull();
    }

    @Test
    void completeSession_대상_세션이_없으면_SESSION_NOT_FOUND() {
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.completeSession(SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void saveNextQuestion_은_수신값을_그대로_저장한다() {
        ArgumentCaptor<Question> captor = ArgumentCaptor.forClass(Question.class);

        writer.saveNextQuestion(SESSION_ID, reaskResult());

        verify(questionRepository).save(captor.capture());
        assertThat(captor.getValue().getQuestionId()).isEqualTo("q_2r");
    }
}
