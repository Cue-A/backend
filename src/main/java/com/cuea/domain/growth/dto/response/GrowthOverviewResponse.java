package com.cuea.domain.growth.dto.response;

import java.util.List;

/**
 * 성장 관리 요약 응답 {@code GET /api/growth}(Issue #129).
 *
 * <p>프론트 계약(`frontend` {@code growthResponse.ts} 의 {@code GrowthOverviewResponse})의
 * 조합 모양을 그대로 유지합니다: {@code latestReport} · {@code weeklyGoal} · {@code badges}.
 *
 * <p><b>이 이슈(#129)에서는 {@code weeklyGoal} 조각만 책임집니다.</b> {@code latestReport}
 * (가장 최근 완료/부분 리포트)와 {@code badges}(획득 배지)는 각자 별도 이슈에서 채웁니다.
 * 그때까지는 조합 응답의 구조를 깨지 않도록 {@code latestReport} 는 {@code null},
 * {@code badges} 는 빈 배열로 내려갑니다. 프론트 변환({@code toGrowthOverview})은 두 값이
 * 각각 null·빈 배열이어도 안전하게 동작합니다.
 *
 * @param latestReport 가장 최근 완료/부분 리포트. #129 범위 밖 → 항상 {@code null}
 * @param weeklyGoal   이번 주 목표·실적. 미설정이면 {@code null}
 * @param badges       획득 배지 목록. #129 범위 밖 → 항상 빈 배열
 */
public record GrowthOverviewResponse(
        Object latestReport,
        WeeklyGoalResponse weeklyGoal,
        List<Object> badges
) {

    /** 이번 주 목표 조각만 채운 응답. 나머지 조각은 각 이슈가 채울 때까지 비워 둡니다. */
    public static GrowthOverviewResponse ofWeeklyGoal(WeeklyGoalResponse weeklyGoal) {
        return new GrowthOverviewResponse(null, weeklyGoal, List.of());
    }
}
