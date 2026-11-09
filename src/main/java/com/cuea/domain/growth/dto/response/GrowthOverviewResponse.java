package com.cuea.domain.growth.dto.response;

import java.util.List;

/**
 * 성장 관리 요약 응답 {@code GET /api/growth}(Issue #129).
 *
 * <p>프론트 계약(`frontend` {@code growthResponse.ts} 의 {@code GrowthOverviewResponse})의
 * 조합 모양을 그대로 유지합니다: {@code latestReport} · {@code weeklyGoal} · {@code badges}.
 *
 * <p>{@code weeklyGoal} 조각은 #129 가, {@code badges} 조각은 #131(배지)이 채웁니다.
 * {@code latestReport} 는 아직 별도 이슈 범위라 {@code null} 입니다. 프론트 변환
 * ({@code toGrowthOverview})은 {@code latestReport} 가 null 이어도 안전하게 동작합니다.
 *
 * @param latestReport 가장 최근 완료/부분 리포트. 현재 범위 밖 → {@code null}
 * @param weeklyGoal   이번 주 목표·실적. 미설정이면 {@code null}
 * @param badges       배지 5종(획득/미획득 포함). 미획득은 {@code earnedAt=null}
 */
public record GrowthOverviewResponse(
        Object latestReport,
        WeeklyGoalResponse weeklyGoal,
        List<BadgeView> badges
) {

    /** 이번 주 목표 조각만 채운 응답. 배지는 빈 배열입니다(연동 전 하위호환용). */
    public static GrowthOverviewResponse ofWeeklyGoal(WeeklyGoalResponse weeklyGoal) {
        return new GrowthOverviewResponse(null, weeklyGoal, List.of());
    }

    /** 이번 주 목표와 배지 5종을 채운 응답. {@code latestReport} 는 현재 범위 밖이라 null 입니다. */
    public static GrowthOverviewResponse of(WeeklyGoalResponse weeklyGoal, List<BadgeView> badges) {
        return new GrowthOverviewResponse(null, weeklyGoal, badges);
    }
}
