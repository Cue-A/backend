package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.report.dto.response.ReportRetryResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportRetryStatus;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.AiReportResultReader;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.ai.dto.AiReportRetryRequest;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
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
 * 재시도 동기 구간. PARTIAL 만 받고, AI 가 받아준 뒤에만 상태를 바꾸는지를 봅니다.
 * 동시 요청의 조건부 UPDATE 는 {@code ReportRepositoryTest} 가 실DB 로 봅니다.
 */
class ReportRetryServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID PUBLIC_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess_1";
    private static final Map<String, Object> GAZE_FAILED = Map.of(
            "report_status", "partial",
            "overall", Map.of("score", 68, "partial", true,
                    "axes_used", List.of("content", "speech"), "axes_failed", List.of("gaze")));

    private ReportRepository reportRepository;
    private ReportRequestAssembler requestAssembler;
    private ReportWriter reportWriter;
    private AiClient aiClient;
    private ReportRetryService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        requestAssembler = mock(ReportRequestAssembler.class);
        reportWriter = mock(ReportWriter.class);
        aiClient = mock(AiClient.class);
        service = new ReportRetryService(new ReportFinder(reportRepository), requestAssembler, reportWriter,
                new AiReportResultReader(JsonMapper.builder().findAndAddModules().build()), aiClient);

        when(requestAssembler.build(SESSION_ID))
                .thenReturn(new AiReportRequest("friendly", "백엔드 개발", null, null, List.of()));
    }

    @Test
    void 실패한_축만_새_시도번호로_AI_에_보내고_재시도를_시작한다() {
        Report partial = report(ReportStatus.PARTIAL, null, GAZE_FAILED);
        givenReport(partial);
        when(aiClient.retryReport(eq(SESSION_ID), anyString(), any())).thenReturn("task_r2");
        when(reportWriter.startRetry(partial, SESSION_ID, "task_r2", List.of("gaze")))
                .thenReturn(report(ReportStatus.PARTIAL, ReportRetryStatus.PROCESSING, GAZE_FAILED));

        ReportRetryResponse response = service.retry(USER_ID, PUBLIC_ID.toString());

        ArgumentCaptor<AiReportRetryRequest> body = ArgumentCaptor.forClass(AiReportRetryRequest.class);
        verify(aiClient).retryReport(eq(SESSION_ID), eq("rpt_sess_1_2"), body.capture());
        assertThat(body.getValue().axes()).containsExactly("gaze");
        assertThat(body.getValue().jobRole()).isEqualTo("백엔드 개발");
        assertThat(response.status()).isEqualTo(ReportStatus.PARTIAL);
        assertThat(response.retry().status()).isEqualTo(ReportRetryStatus.PROCESSING);
        assertThat(response.retry().axes()).containsExactly("gaze");
    }

    @Test
    void 이전_재시도가_실패했으면_다시_할_수_있다() {
        Report partial = report(ReportStatus.PARTIAL, ReportRetryStatus.FAILED, GAZE_FAILED);
        givenReport(partial);
        when(aiClient.retryReport(eq(SESSION_ID), anyString(), any())).thenReturn("task_r2");
        when(reportWriter.startRetry(any(), any(), any(), any()))
                .thenReturn(report(ReportStatus.PARTIAL, ReportRetryStatus.PROCESSING, GAZE_FAILED));

        service.retry(USER_ID, PUBLIC_ID.toString());

        verify(reportWriter).startRetry(partial, SESSION_ID, "task_r2", List.of("gaze"));
    }

    /** FAILED 는 분석 작업 등록 재요청으로 복구합니다. 진행 중 · 완료는 다시 분석할 축이 없습니다. */
    @Test
    void PARTIAL_이_아니면_REPORT_NOT_RETRYABLE() {
        for (ReportStatus status : List.of(ReportStatus.PROCESSING, ReportStatus.COMPLETED, ReportStatus.FAILED)) {
            givenReport(report(status, null, GAZE_FAILED));

            assertErrorCode(ErrorCode.REPORT_NOT_RETRYABLE);
        }
        verify(aiClient, never()).retryReport(anyString(), anyString(), any());
    }

    @Test
    void 이미_재시도_중이면_REPORT_RETRY_IN_PROGRESS() {
        givenReport(report(ReportStatus.PARTIAL, ReportRetryStatus.PROCESSING, GAZE_FAILED));

        assertErrorCode(ErrorCode.REPORT_RETRY_IN_PROGRESS);
        verify(aiClient, never()).retryReport(anyString(), anyString(), any());
    }

    /** AI 가 axes 가 비면 400 을 줍니다. 보내기 전에 막습니다. */
    @Test
    void 실패한_축이_없으면_AI_를_부르지_않고_REPORT_NOT_RETRYABLE() {
        givenReport(report(ReportStatus.PARTIAL, null, Map.of(
                "report_status", "partial", "overall", Map.of("axes_failed", List.of()))));

        assertErrorCode(ErrorCode.REPORT_NOT_RETRYABLE);
        verify(aiClient, never()).retryReport(anyString(), anyString(), any());
    }

    /** AI 가 거절하면 리포트를 건드리지 않습니다. 그대로 PARTIAL 이고 다시 누를 수 있습니다. */
    @Test
    void AI_요청이_실패하면_재시도를_시작하지_않는다() {
        givenReport(report(ReportStatus.PARTIAL, null, GAZE_FAILED));
        when(aiClient.retryReport(eq(SESSION_ID), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));

        assertErrorCode(ErrorCode.AI_UNAVAILABLE);
        verify(reportWriter, never()).startRetry(any(), any(), any(), any());
    }

    @Test
    void 없거나_남의_리포트면_REPORT_NOT_FOUND() {
        when(reportRepository.findByPublicIdAndSession_User_UserId(any(), anyString())).thenReturn(Optional.empty());

        assertErrorCode(ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    void UUID_가_아니면_조회하지_않고_REPORT_NOT_FOUND() {
        assertThatThrownBy(() -> service.retry(USER_ID, "not-a-uuid"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
        verify(reportRepository, never()).findByPublicIdAndSession_User_UserId(any(), anyString());
    }

    private void assertErrorCode(ErrorCode expected) {
        assertThatThrownBy(() -> service.retry(USER_ID, PUBLIC_ID.toString()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private void givenReport(Report report) {
        when(reportRepository.findByPublicIdAndSession_User_UserId(PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(report));
    }

    private Report report(ReportStatus status, ReportRetryStatus retryStatus, Map<String, Object> reportData) {
        InterviewSession session = InterviewSession.builder().sessionId(SESSION_ID).build();
        return Report.builder().reportId(10L).publicId(PUBLIC_ID).session(session)
                .status(status).retryStatus(retryStatus).attempt(1).reportData(reportData).build();
    }
}
