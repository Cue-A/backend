package com.cuea.domain.report.service;

import com.cuea.domain.company.service.CompanyProfileFormatter;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.infrastructure.ai.dto.AiReportAnswer;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 요청 본문 조립. 계약과 어긋나면 AI 가 400 을 주거나 리포트 전체가 실패합니다. */
class ReportRequestAssemblerTest {

    private static final String SESSION_ID = "sess_1";

    private InterviewSessionQueryService sessionQueryService;
    private ReportRequestAssembler assembler;

    @BeforeEach
    void setUp() {
        sessionQueryService = mock(InterviewSessionQueryService.class);
        PresignedUrlIssuer presignedUrlIssuer = mock(PresignedUrlIssuer.class);
        when(presignedUrlIssuer.issueRecordingDownload(anyString()))
                .thenAnswer(invocation -> "https://s3/" + invocation.getArgument(0) + "?signed");
        assembler = new ReportRequestAssembler(sessionQueryService, new CompanyProfileFormatter(), presignedUrlIssuer);

        when(sessionQueryService.getSessionForInternal(SESSION_ID)).thenReturn(InterviewSession.builder()
                .sessionId(SESSION_ID)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(3)
                .persona(Persona.PRESSURE)
                .status(SessionStatus.COMPLETED)
                .build());
    }

    @Test
    void 질문을_나간_순서대로_되묻기까지_소문자_type_으로_담는다() {
        when(sessionQueryService.findQuestionsInOrder(SESSION_ID)).thenReturn(List.of(
                question("q_1", QuestionType.QUESTION, 1, "a/q_1.webm", "a/q_1_video.mp4", null),
                question("q_1r", QuestionType.REASK, 1, "a/q_1r.webm", null, "q_1"),
                question("q_2", QuestionType.FOLLOWUP, 2, "a/q_2.webm", null, null)));

        AiReportRequest request = assembler.build(SESSION_ID);

        assertThat(request.persona()).isEqualTo("pressure");
        assertThat(request.jobRole()).isEqualTo("백엔드 개발");
        assertThat(request.companyId()).isNull();
        assertThat(request.companyProfileOverride()).isNull();
        assertThat(request.answers()).extracting(AiReportAnswer::questionId).containsExactly("q_1", "q_1r", "q_2");
        assertThat(request.answers()).extracting(AiReportAnswer::type).containsExactly("question", "reask", "followup");
        assertThat(request.answers().get(1).reaskOf()).isEqualTo("q_1");
    }

    @Test
    void 녹음은_presigned_URL_로_주고_영상이_없으면_null() {
        when(sessionQueryService.findQuestionsInOrder(SESSION_ID)).thenReturn(List.of(
                question("q_1", QuestionType.QUESTION, 1, "a/q_1.webm", "a/q_1_video.mp4", null),
                question("q_2", QuestionType.FOLLOWUP, 2, "a/q_2.webm", null, null)));

        List<AiReportAnswer> answers = assembler.build(SESSION_ID).answers();

        assertThat(answers.get(0).audioUrl()).isEqualTo("https://s3/a/q_1.webm?signed");
        assertThat(answers.get(0).videoUrl()).isEqualTo("https://s3/a/q_1_video.mp4?signed");
        assertThat(answers.get(1).videoUrl()).isNull();
    }

    /** 빈 녹음 URL 을 보내면 AI 가 MEDIA_FETCH_FAILED 로 리포트 전체를 실패시킵니다. */
    @Test
    void 녹음이_없는_질문은_뺀다() {
        when(sessionQueryService.findQuestionsInOrder(SESSION_ID)).thenReturn(List.of(
                question("q_1", QuestionType.QUESTION, 1, "a/q_1.webm", null, null),
                question("q_2", QuestionType.FOLLOWUP, 2, null, null, null)));

        assertThat(assembler.build(SESSION_ID).answers())
                .extracting(AiReportAnswer::questionId).containsExactly("q_1");
    }

    private Question question(String questionId, QuestionType type, int number,
                              String audioKey, String videoKey, String reaskOf) {
        return Question.builder()
                .sessionId(SESSION_ID)
                .questionId(questionId)
                .type(type)
                .text("질문")
                .category(type == QuestionType.REASK ? null : "협업·갈등")
                .difficulty(type == QuestionType.REASK ? null : "L2")
                .reaskOf(reaskOf)
                .questionNumber(number)
                .answerAudioObjectKey(audioKey)
                .answerVideoObjectKey(videoKey)
                .build();
    }
}
