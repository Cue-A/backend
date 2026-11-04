package com.cuea.domain.home.controller;

import com.cuea.common.result.Result;
import com.cuea.domain.home.dto.response.RecentReportListResponse;
import com.cuea.domain.home.dto.response.RecentReportResponse;
import com.cuea.domain.home.service.RecentReportQueryService;
import com.cuea.domain.report.entity.ReportStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨트롤러가 서비스에 위임하고 {@code Result<T>} 로 감싸는지 Spring 컨텍스트 없이 확인합니다.
 * 로그인 가드({@code @CurrentUser})는 {@code EndpointAuthGuardTest} 가 따로 봅니다.
 */
class HomeControllerTest {

    @Test
    void 최근_리포트를_서비스에_위임하고_Result_로_감싼다() {
        RecentReportQueryService service = mock(RecentReportQueryService.class);
        RecentReportListResponse body = RecentReportListResponse.of(List.of(
                new RecentReportResponse(UUID.randomUUID().toString(), "네이버", "백엔드",
                        9, ReportStatus.COMPLETED, 92, OffsetDateTime.parse("2026-10-28T09:00:00Z"))));
        when(service.recentReports("user-1", 3)).thenReturn(body);

        HomeController controller = new HomeController(service);
        Result<RecentReportListResponse> result = controller.recentReports("user-1", 3);

        verify(service).recentReports("user-1", 3);
        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isNull();
        assertThat(result.data()).isSameAs(body);
    }

    @Test
    void limit_이_없으면_null_그대로_서비스에_넘긴다() {
        RecentReportQueryService service = mock(RecentReportQueryService.class);
        RecentReportListResponse empty = RecentReportListResponse.of(List.of());
        when(service.recentReports("user-1", null)).thenReturn(empty);

        HomeController controller = new HomeController(service);
        Result<RecentReportListResponse> result = controller.recentReports("user-1", null);

        verify(service).recentReports("user-1", null);
        assertThat(result.success()).isTrue();
        assertThat(result.data().reports()).isEmpty();
    }
}
