package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.report.dto.response.ReportRequestResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 동기 구간(①~⑥)의 분기를 봅니다. 핵심은 두 가지입니다.
 * <ul>
 *   <li>AI 등록이 실패하면 리포트 행을 남기지 않는다</li>
 *   <li>재요청은 FAILED 만 허용하고, 그때는 시도 번호를 올린 새 멱등 키를 쓴다</li>
 * </ul>
 */
class ReportRequestServiceTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "sess_1";

    private InterviewSessionQueryService sessionQueryService;
    private ReportRepository reportRepository;
    private ReportRequestAssembler requestAssembler;
    private ReportWriter reportWriter;
    private AiClient aiClient;
    private ReportRequestService service;

    @BeforeEach
    void setUp() {
        sessionQueryService = mock(InterviewSessionQueryService.class);
        reportRepository = mock(ReportRepository.class);
        requestAssembler = mock(ReportRequestAssembler.class);
        reportWriter = mock(ReportWriter.class);
        aiClient = mock(AiClient.class);
        service = new ReportRequestService(
                sessionQueryService, reportRepository, requestAssembler, reportWriter, aiClient);

        when(requestAssembler.build(SESSION_ID))
                .thenReturn(new AiReportRequest("friendly", "백엔드 개발", null, null, List.of()));
        when(reportRepository.findBySession_SessionIdAndSession_User_UserId(SESSION_ID, USER_ID)).thenReturn(Optional.empty());
    }

    @Test
    void 끝난_세션이면_AI_에_등록하고_PROCESSING_으로_202() {
        InterviewSession session = session(SessionStatus.COMPLETED);
        givenSession(session);
        when(aiClient.requestReport(eq(SESSION_ID), anyString(), any())).thenReturn("task_r1");
        Report created = report(ReportStatus.PROCESSING, 1);
        when(reportWriter.createProcessing(session, "task_r1")).thenReturn(created);

        ReportRequestResponse response = service.request(USER_ID, SESSION_ID);

        verify(aiClient).requestReport(eq(SESSION_ID), eq("rpt_sess_1_1"), any());
        assertThat(response.reportId()).isEqualTo(created.getPublicId().toString());
        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.status()).isEqualTo(ReportStatus.PROCESSING);
    }

    @Test
    void 남의_세션이거나_없으면_404_이고_AI_를_부르지_않는다() {
        when(sessionQueryService.getOwnedSession(SESSION_ID, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertError(ErrorCode.SESSION_NOT_FOUND);
        verify(aiClient, never()).requestReport(anyString(), anyString(), any());
    }

    @Test
    void 진행_중인_세션이면_409_SESSION_NOT_COMPLETED() {
        givenSession(session(SessionStatus.IN_PROGRESS));

        assertError(ErrorCode.SESSION_NOT_COMPLETED);
        verify(aiClient, never()).requestReport(anyString(), anyString(), any());
    }

    @Test
    void 중단된_세션이면_409_SESSION_ABORTED() {
        givenSession(session(SessionStatus.ABORTED));

        assertError(ErrorCode.SESSION_ABORTED);
    }

    @Test
    void 리포트가_이미_있으면_409_이고_AI_를_부르지_않는다() {
        givenSession(session(SessionStatus.COMPLETED));
        for (ReportStatus status : List.of(ReportStatus.PROCESSING, ReportStatus.COMPLETED, ReportStatus.PARTIAL)) {
            when(reportRepository.findBySession_SessionIdAndSession_User_UserId(SESSION_ID, USER_ID)).thenReturn(Optional.of(report(status, 1)));

            assertError(ErrorCode.REPORT_ALREADY_EXISTS);
        }
        verify(aiClient, never()).requestReport(anyString(), anyString(), any());
    }

    /** 같은 키로 보내면 AI 가 실패한 task_id 를 그대로 돌려주므로 시도 번호를 올려야 합니다. */
    @Test
    void 실패한_리포트는_시도_번호를_올린_새_키로_다시_요청하고_같은_행을_쓴다() {
        givenSession(session(SessionStatus.COMPLETED));
        Report failed = report(ReportStatus.FAILED, 2);
        when(reportRepository.findBySession_SessionIdAndSession_User_UserId(SESSION_ID, USER_ID)).thenReturn(Optional.of(failed));
        when(aiClient.requestReport(eq(SESSION_ID), anyString(), any())).thenReturn("task_r3");
        when(reportWriter.reopenFailed(failed, SESSION_ID, "task_r3")).thenReturn(report(ReportStatus.PROCESSING, 3));

        service.request(USER_ID, SESSION_ID);

        verify(aiClient).requestReport(eq(SESSION_ID), eq("rpt_sess_1_3"), any());
        verify(reportWriter).reopenFailed(failed, SESSION_ID, "task_r3");
        verify(reportWriter, never()).createProcessing(any(), anyString());
    }

    @Test
    void AI_등록이_실패하면_그_오류를_그대로_내고_행을_만들지_않는다() {
        givenSession(session(SessionStatus.COMPLETED));
        for (ErrorCode code : List.of(ErrorCode.REPORT_TOO_SHORT, ErrorCode.AI_UNAVAILABLE, ErrorCode.AI_TIMEOUT)) {
            when(aiClient.requestReport(eq(SESSION_ID), anyString(), any())).thenThrow(new BusinessException(code));

            assertError(code);
        }
        verify(reportWriter, never()).createProcessing(any(), anyString());
        verify(reportWriter, never()).reopenFailed(any(), anyString(), anyString());
    }

    /** 동시 요청 두 개가 모두 중복 검사를 통과해도 session_id UNIQUE 에서 한쪽만 남습니다. */
    @Test
    void 동시_요청으로_UNIQUE_에_걸리면_409_REPORT_ALREADY_EXISTS() {
        givenSession(session(SessionStatus.COMPLETED));
        when(aiClient.requestReport(eq(SESSION_ID), anyString(), any())).thenReturn("task_r1");
        when(reportWriter.createProcessing(any(), anyString()))
                .thenThrow(new DataIntegrityViolationException("uk_report_session"));

        assertError(ErrorCode.REPORT_ALREADY_EXISTS);
    }

    private void assertError(ErrorCode expected) {
        assertThatThrownBy(() -> service.request(USER_ID, SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }

    private void givenSession(InterviewSession session) {
        when(sessionQueryService.getOwnedSession(SESSION_ID, USER_ID)).thenReturn(session);
    }

    private InterviewSession session(SessionStatus status) {
        return InterviewSession.builder()
                .sessionId(SESSION_ID)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(6)
                .persona(Persona.FRIENDLY)
                .status(status)
                .build();
    }

    private Report report(ReportStatus status, int attempt) {
        return Report.builder()
                .reportId(10L)
                .publicId(UUID.randomUUID())
                .status(status)
                .attempt(attempt)
                .build();
    }
}
