package com.cuea.domain.growth.dto.response;

/**
 * 주간 목표 응답(Issue #129). {@code GET /api/growth} 의 {@code weeklyGoal} 조각과
 * {@code PATCH /api/growth/weekly-goal} 의 응답 본문에 같은 모양으로 씁니다.
 * 프론트 계약(`frontend` {@code growthResponse.ts} 의 {@code WeeklyGoalResponse})과 일치합니다.
 *
 * <p>목표 미설정 시에는 이 객체 자체가 {@code null} 로 내려갑니다(상위에서 {@code weeklyGoal: null}).
 * 프론트는 {@code weeklyGoal} 이 null 이면 "목표 미설정" 으로 봅니다. 따라서 이 객체 안의
 * {@code targetValue} 는 항상 실제 목표값(1~7)이며 null 이 아닙니다.
 *
 * @param targetValue 이번 주 목표 횟수 (1~7). {@code goal.target_value}
 * @param doneCount   이번 주(월~일, KST) 완료한 연습(COMPLETED 세션) 수. 재연습 포함
 */
public record WeeklyGoalResponse(
        int targetValue,
        long doneCount
) {
}
