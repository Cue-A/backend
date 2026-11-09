package com.cuea.domain.growth.controller;

import com.cuea.common.result.Result;
import com.cuea.domain.growth.badge.BadgeQueryService;
import com.cuea.domain.growth.dto.response.BadgeView;
import com.cuea.domain.growth.dto.response.GrowthOverviewResponse;
import com.cuea.domain.growth.dto.response.WeeklyGoalResponse;
import com.cuea.domain.growth.service.WeeklyGoalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/growth} 가 weeklyGoal(#129)과 badges(#131)를 조합 응답으로 묶는지 Spring 컨텍스트
 * 없이 봅니다(Issue #131 연동). 배지 목록은 {@link BadgeQueryService#overview} 결과를 그대로 싣습니다.
 */
class GrowthControllerTest {

    private static final String USER = "user-1";

    private WeeklyGoalService weeklyGoalService;
    private BadgeQueryService badgeQueryService;
    private GrowthController controller;

    @BeforeEach
    void setUp() {
        weeklyGoalService = mock(WeeklyGoalService.class);
        badgeQueryService = mock(BadgeQueryService.class);
        controller = new GrowthController(weeklyGoalService, badgeQueryService);
    }

    @Test
    void overview_는_weeklyGoal_과_badges_5종을_조합해_돌려준다() {
        WeeklyGoalResponse weeklyGoal = new WeeklyGoalResponse(3, 2);
        List<BadgeView> badges = List.of(
                new BadgeView("FIRST_SESSION", "첫 연습", "brand", OffsetDateTime.parse("2026-07-01T10:00:00+09:00")),
                new BadgeView("STREAK_3", "3일 연속", "warning", null),
                new BadgeView("PRESSURE_CLEAR", "압박 극복", "danger", null),
                new BadgeView("RISING", "우상향", "info", null),
                new BadgeView("TEN_SESSIONS", "10회 달성", "success", null));
        when(weeklyGoalService.getThisWeek(USER)).thenReturn(weeklyGoal);
        when(badgeQueryService.overview(USER)).thenReturn(badges);

        Result<GrowthOverviewResponse> result = controller.overview(USER);
        GrowthOverviewResponse body = result.data();

        assertThat(body.latestReport()).isNull();
        assertThat(body.weeklyGoal()).isEqualTo(weeklyGoal);
        assertThat(body.badges()).hasSize(5);
        assertThat(body.badges()).isEqualTo(badges);
        assertThat(body.badges()).extracting(BadgeView::badgeCode).containsExactly(
                "FIRST_SESSION", "STREAK_3", "PRESSURE_CLEAR", "RISING", "TEN_SESSIONS");
    }

    @Test
    void overview_는_목표_미설정이면_weeklyGoal_null_이어도_badges_는_내려간다() {
        List<BadgeView> badges = List.of(
                new BadgeView("FIRST_SESSION", "첫 연습", "brand", null),
                new BadgeView("STREAK_3", "3일 연속", "warning", null),
                new BadgeView("PRESSURE_CLEAR", "압박 극복", "danger", null),
                new BadgeView("RISING", "우상향", "info", null),
                new BadgeView("TEN_SESSIONS", "10회 달성", "success", null));
        when(weeklyGoalService.getThisWeek(USER)).thenReturn(null);
        when(badgeQueryService.overview(USER)).thenReturn(badges);

        GrowthOverviewResponse body = controller.overview(USER).data();

        assertThat(body.weeklyGoal()).isNull();
        assertThat(body.badges()).hasSize(5);
    }
}
