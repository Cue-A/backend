package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.InterviewRecordingsResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 면접 다시보기 재생목록 조립을 검증합니다. (Issue #103)
 *
 * <p>presigned URL 은 key 를 그대로 붙인 가짜 값으로 돌려줘, 어느 key 로 서명했는지를
 * 응답에서 바로 확인합니다.
 */
class InterviewRecordingServiceTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "sess_1";

    private InterviewSessionQueryService sessionQueryService;
    private QuestionRepository questionRepository;
    private PresignedUrlIssuer presignedUrlIssuer;
    private InterviewRecordingService service;

    @BeforeEach
    void setUp() {
        sessionQueryService = mock(InterviewSessionQueryService.class);
        questionRepository = mock(QuestionRepository.class);
        presignedUrlIssuer = mock(PresignedUrlIssuer.class);
        service = new InterviewRecordingService(sessionQueryService, questionRepository, presignedUrlIssuer);

        when(presignedUrlIssuer.issueRecordingDownload(anyString()))
                .thenAnswer(invocation -> "signed:" + invocation.getArgument(0));
        when(presignedUrlIssuer.issueQuestionAudioPlayback(anyString()))
                .thenAnswer(invocation -> Optional.of("signed:" + invocation.getArgument(0)));
    }

    @Test
    void 답변한_질문을_나간_순서대로_질문음성_녹음_영상_URL_과_함께_돌려준다() {
        givenSession(SessionStatus.COMPLETED);
        givenQuestions(
                question("q_1", QuestionType.QUESTION, 1, "tts", "sessions/sess_1/answers/q_1.webm",
                        "sessions/sess_1/answers/q_1_video.webm"),
                question("q_1r", QuestionType.REASK, 1, null, "sessions/sess_1/answers/q_1r.webm", null));

        InterviewRecordingsResponse response = service.getRecordings(USER_ID, SESSION_ID);

        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.items()).extracting(InterviewRecordingsResponse.Item::questionId)
                .containsExactly("q_1", "q_1r");

        InterviewRecordingsResponse.Item first = response.items().get(0);
        assertThat(first.questionNumber()).isEqualTo(1);
        assertThat(first.type()).isEqualTo("QUESTION");
        assertThat(first.questionText()).isEqualTo("q_1 질문");
        // 질문 음성은 AI 가 준 URL 이 아니라 계약 key 로 서명합니다.
        assertThat(first.questionAudioUrl()).isEqualTo("signed:sessions/sess_1/questions/q_1.mp3");
        assertThat(first.answerAudioUrl()).isEqualTo("signed:sessions/sess_1/answers/q_1.webm");
        assertThat(first.answerVideoUrl()).isEqualTo("signed:sessions/sess_1/answers/q_1_video.webm");

        InterviewRecordingsResponse.Item reask = response.items().get(1);
        assertThat(reask.type()).isEqualTo("REASK");
        assertThat(reask.questionNumber()).isEqualTo(1);
    }

    @Test
    void 영상이_하나라도_있으면_hasVideo_는_true_다() {
        givenSession(SessionStatus.COMPLETED);
        givenQuestions(
                question("q_1", QuestionType.QUESTION, 1, "tts", "sessions/sess_1/answers/q_1.webm", null),
                question("q_2", QuestionType.FOLLOWUP, 2, "tts", "sessions/sess_1/answers/q_2.webm",
                        "sessions/sess_1/answers/q_2_video.webm"));

        InterviewRecordingsResponse response = service.getRecordings(USER_ID, SESSION_ID);

        assertThat(response.hasVideo()).isTrue();
        assertThat(response.items().get(0).answerVideoUrl()).isNull();
    }

    @Test
    void 카메라를_한_번도_안_쓴_세션은_200_으로_hasVideo_false_와_녹음만_돌려준다() {
        givenSession(SessionStatus.COMPLETED);
        givenQuestions(
                question("q_1", QuestionType.QUESTION, 1, "tts", "sessions/sess_1/answers/q_1.webm", null),
                question("q_2", QuestionType.QUESTION, 2, "tts", "sessions/sess_1/answers/q_2.webm", " "));

        InterviewRecordingsResponse response = service.getRecordings(USER_ID, SESSION_ID);

        assertThat(response.hasVideo()).isFalse();
        assertThat(response.items()).extracting(InterviewRecordingsResponse.Item::answerVideoUrl)
                .containsOnlyNulls();
        assertThat(response.items()).extracting(InterviewRecordingsResponse.Item::answerAudioUrl)
                .doesNotContainNull();
    }

    @Test
    void 답변_녹음이_없는_질문은_목록에서_뺀다() {
        givenSession(SessionStatus.COMPLETED);
        givenQuestions(
                question("q_1", QuestionType.QUESTION, 1, "tts", "sessions/sess_1/answers/q_1.webm", null),
                question("q_2", QuestionType.QUESTION, 2, "tts", null, null),
                question("q_3", QuestionType.QUESTION, 3, "tts", "", null));

        InterviewRecordingsResponse response = service.getRecordings(USER_ID, SESSION_ID);

        assertThat(response.items()).extracting(InterviewRecordingsResponse.Item::questionId)
                .containsExactly("q_1");
    }

    @Test
    void 질문_음성이_없으면_서명하지_않고_null_이다() {
        givenSession(SessionStatus.COMPLETED);
        givenQuestions(question("q_1", QuestionType.QUESTION, 1, null, "sessions/sess_1/answers/q_1.webm", null));

        InterviewRecordingsResponse response = service.getRecordings(USER_ID, SESSION_ID);

        verify(presignedUrlIssuer, never()).issueQuestionAudioPlayback(anyString());
        assertThat(response.items().get(0).questionAudioUrl()).isNull();
    }

    @Test
    void 질문_음성_서명이_실패하면_그_URL_만_null_이고_나머지는_내려준다() {
        givenSession(SessionStatus.COMPLETED);
        givenQuestions(question("q_1", QuestionType.QUESTION, 1, "tts", "sessions/sess_1/answers/q_1.webm", null));
        when(presignedUrlIssuer.issueQuestionAudioPlayback("sessions/sess_1/questions/q_1.mp3"))
                .thenReturn(Optional.empty());

        InterviewRecordingsResponse response = service.getRecordings(USER_ID, SESSION_ID);

        assertThat(response.items().get(0).questionAudioUrl()).isNull();
        assertThat(response.items().get(0).answerAudioUrl()).isEqualTo("signed:sessions/sess_1/answers/q_1.webm");
    }

    @Test
    void 진행_중인_세션이면_SESSION_NOT_COMPLETED_이고_질문을_읽지_않는다() {
        givenSession(SessionStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.getRecordings(USER_ID, SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_COMPLETED);
        verifyNoInteractions(questionRepository, presignedUrlIssuer);
    }

    @Test
    void 중단된_세션이면_SESSION_ABORTED_다() {
        givenSession(SessionStatus.ABORTED);

        assertThatThrownBy(() -> service.getRecordings(USER_ID, SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_ABORTED);
    }

    @Test
    void 없거나_남의_세션이면_SESSION_NOT_FOUND_를_그대로_전파한다() {
        when(sessionQueryService.getOwnedSession(SESSION_ID, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> service.getRecordings(USER_ID, SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
        verifyNoInteractions(questionRepository);
    }

    private void givenSession(SessionStatus status) {
        InterviewSession session = InterviewSession.builder()
                .sessionId(SESSION_ID)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(3)
                .persona(Persona.FRIENDLY)
                .status(status)
                .build();
        when(sessionQueryService.getOwnedSession(SESSION_ID, USER_ID)).thenReturn(session);
    }

    private void givenQuestions(Question... questions) {
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(questions));
    }

    private static Question question(String questionId, QuestionType type, int questionNumber,
                                     String audioUrl, String answerAudioKey, String answerVideoKey) {
        Question question = Question.builder()
                .sessionId(SESSION_ID).questionId(questionId)
                .type(type).text(questionId + " 질문")
                .audioUrl(audioUrl)
                .questionNumber(questionNumber).topicIndex(0).build();
        question.attachAnswerMedia(answerAudioKey, answerVideoKey, false);
        return question;
    }
}
