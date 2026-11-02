package com.cuea.domain.home.controller;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
import com.cuea.domain.home.dto.response.HomePracticeResponse;
import com.cuea.domain.home.dto.response.HomeStatsResponse;
import com.cuea.domain.home.dto.response.RecentReportListResponse;
import com.cuea.domain.home.dto.response.RecentReportResponse;
import com.cuea.domain.home.service.HomePracticeQueryService;
import com.cuea.domain.home.service.HomeStatsQueryService;
import com.cuea.domain.home.service.PracticePeriod;
import com.cuea.domain.home.service.RecentReportQueryService;
import com.cuea.domain.report.entity.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨트롤러가 서비스에 위임하고 {@code Result<T>} 로 감싸는지 Spring 컨텍스트 없이 확인합니다.
 * 로그인 가드({@code @CurrentUser})는 {@code EndpointAuthGuardTest} 가 따로 봅니다.
 */
class HomeControllerTest {

    private RecentReportQueryService recentReportQueryService;
    private HomeStatsQueryService homeStatsQueryService;
    private HomePracticeQueryService homePracticeQueryService;
    private HomeController controller;

    @BeforeEach
    void setUp() {
        recentReportQueryService = mock(RecentReportQueryService.class);
        homeStatsQueryService = mock(HomeStatsQueryService.class);
        homePracticeQueryService = mock(HomePracticeQueryService.class);
        controller = new HomeController(
                recentReportQueryService, homeStatsQueryService, homePracticeQueryService);
    }

    @Test
    void 최근_리포트를_서비스에_위임하고_Result_로_감싼다() {
        RecentReportListResponse body = RecentReportListResponse.of(List.of(
                new RecentReportResponse(UUID.randomUUID().toString(), "네이버", "백엔드",
                        9, ReportStatus.COMPLETED, 92, OffsetDateTime.parse("2026-10-28T09:00:00Z"))));
        when(recentReportQueryService.recentReports("user-1", 3)).thenReturn(body);

        Result<RecentReportListResponse> result = controller.recentReports("user-1", 3);

        verify(recentReportQueryService).recentReports("user-1", 3);
        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isNull();
        assertThat(result.data()).isSameAs(body);
    }

    @Test
    void limit_이_없으면_null_그대로_서비스에_넘긴다() {
        RecentReportListResponse empty = RecentReportListResponse.of(List.of());
        when(recentReportQueryService.recentReports("user-1", null)).thenReturn(empty);

        Result<RecentReportListResponse> result = controller.recentReports("user-1", null);

        verify(recentReportQueryService).recentReports("user-1", null);
        assertThat(result.success()).isTrue();
        assertThat(result.data().reports()).isEmpty();
    }

    @Test
    void 통계를_서비스에_위임하고_Result_로_감싼다() {
        HomeStatsResponse body = new HomeStatsResponse(
                new HomeStatsResponse.ThisMonth(11, 7, 2),
                new HomeStatsResponse.Streak(4, 16));
        when(homeStatsQueryService.stats("user-1")).thenReturn(body);

        Result<HomeStatsResponse> result = controller.stats("user-1");

        verify(homeStatsQueryService).stats("user-1");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isSameAs(body);
    }

    @Test
    void 연습기록을_파싱한_기간으로_서비스에_위임한다() {
        HomePracticeResponse body = new HomePracticeResponse(
                "10w", "2026-09-07", "2026-11-15", List.of(), 0, 0, null, null,
                new HomePracticeResponse.ScoreTrend("session", List.of()));
        when(homePracticeQueryService.practice("user-1", PracticePeriod.TEN_WEEKS)).thenReturn(body);

        Result<HomePracticeResponse> result = controller.practice("user-1", "10w");

        verify(homePracticeQueryService).practice("user-1", PracticePeriod.TEN_WEEKS);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isSameAs(body);
    }

    @Test
    void period_가_없으면_기본값_4w_로_위임한다() {
        HomePracticeResponse body = new HomePracticeResponse(
                "4w", "2026-10-19", "2026-11-15", List.of(), 0, 0, null, null,
                new HomePracticeResponse.ScoreTrend("session", List.of()));
        when(homePracticeQueryService.practice("user-1", PracticePeriod.FOUR_WEEKS)).thenReturn(body);

        Result<HomePracticeResponse> result = controller.practice("user-1", null);

        verify(homePracticeQueryService).practice("user-1", PracticePeriod.FOUR_WEEKS);
        assertThat(result.data().period()).isEqualTo("4w");
    }

    @Test
    void 허용하지_않는_period_는_INVALID_REQUEST_로_막는다() {
        assertThatThrownBy(() -> controller.practice("user-1", "3w"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
