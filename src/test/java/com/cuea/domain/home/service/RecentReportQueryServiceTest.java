package com.cuea.domain.home.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.home.dto.response.RecentReportListResponse;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.RecentReportRow;
import com.cuea.domain.report.service.ReportSummaryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecentReportQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final OffsetDateTime PRACTICED_ON = OffsetDateTime.parse("2026-10-28T09:00:00Z");

    private ReportSummaryQueryService reportSummaryQueryService;
    private RecentReportQueryService service;

    @BeforeEach
    void setUp() {
        reportSummaryQueryService = mock(ReportSummaryQueryService.class);
        service = new RecentReportQueryService(reportSummaryQueryService);
    }

    @Test
    void 리포트_도메인_서비스가_준_행을_DTO_로_매핑한다() {
        UUID publicId = UUID.randomUUID();
        RecentReportRow row = new RecentReportRow(
                publicId, "네이버", "백엔드", 9, ReportStatus.COMPLETED, 92, PRACTICED_ON);
        when(reportSummaryQueryService.findRecentViewable(anyString(), anyInt())).thenReturn(List.of(row));

        RecentReportListResponse response = service.recentReports(USER_ID, 3);

        assertThat(response.reports()).singleElement().satisfies(report -> {
            assertThat(report.reportId()).isEqualTo(publicId.toString());
            assertThat(report.companyName()).isEqualTo("네이버");
            assertThat(report.jobRole()).isEqualTo("백엔드");
            assertThat(report.questionCount()).isEqualTo(9);
            assertThat(report.status()).isEqualTo(ReportStatus.COMPLETED);
            assertThat(report.scoreTotal()).isEqualTo(92);
            assertThat(report.practicedOn()).isEqualTo(PRACTICED_ON);
        });
    }

    @Test
    void limit_을_비우면_기본_3건을_요청한다() {
        when(reportSummaryQueryService.findRecentViewable(anyString(), anyInt())).thenReturn(List.of());

        service.recentReports(USER_ID, null);

        assertThat(captureLimit()).isEqualTo(3);
    }

    @Test
    void limit_1_과_10_은_그대로_넘긴다() {
        when(reportSummaryQueryService.findRecentViewable(anyString(), anyInt())).thenReturn(List.of());

        service.recentReports(USER_ID, 1);
        service.recentReports(USER_ID, 10);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(reportSummaryQueryService, org.mockito.Mockito.times(2))
                .findRecentViewable(anyString(), limit.capture());
        assertThat(limit.getAllValues()).containsExactly(1, 10);
    }

    @Test
    void limit_이_0_이면_400_이고_조회하지_않는다() {
        assertThatThrownBy(() -> service.recentReports(USER_ID, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(reportSummaryQueryService);
    }

    @Test
    void limit_이_11_이면_400_이다() {
        assertThatThrownBy(() -> service.recentReports(USER_ID, 11))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(reportSummaryQueryService, never()).findRecentViewable(anyString(), anyInt());
    }

    @Test
    void limit_이_음수면_400_이다() {
        assertThatThrownBy(() -> service.recentReports(USER_ID, -1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 리포트가_없으면_빈_목록이다() {
        when(reportSummaryQueryService.findRecentViewable(anyString(), anyInt())).thenReturn(List.of());

        RecentReportListResponse response = service.recentReports(USER_ID, 3);

        assertThat(response.reports()).isEmpty();
    }

    @Test
    void PARTIAL_점수가_null_이면_그대로_null_로_매핑한다() {
        RecentReportRow row = new RecentReportRow(
                UUID.randomUUID(), null, "데이터", 6, ReportStatus.PARTIAL, null, PRACTICED_ON);
        when(reportSummaryQueryService.findRecentViewable(anyString(), anyInt())).thenReturn(List.of(row));

        RecentReportListResponse response = service.recentReports(USER_ID, 3);

        assertThat(response.reports()).singleElement().satisfies(report -> {
            assertThat(report.status()).isEqualTo(ReportStatus.PARTIAL);
            assertThat(report.scoreTotal()).isNull();
            assertThat(report.companyName()).isNull();
        });
    }

    @Test
    void 조회는_요청한_사용자_ID_로_좁힌다() {
        when(reportSummaryQueryService.findRecentViewable(anyString(), anyInt())).thenReturn(List.of());

        service.recentReports("owner-42", 3);

        verify(reportSummaryQueryService).findRecentViewable(eq("owner-42"), anyInt());
    }

    private int captureLimit() {
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(reportSummaryQueryService, org.mockito.Mockito.atLeastOnce())
                .findRecentViewable(anyString(), limit.capture());
        return limit.getValue();
    }
}
