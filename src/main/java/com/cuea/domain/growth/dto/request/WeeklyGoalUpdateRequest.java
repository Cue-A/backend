package com.cuea.domain.growth.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PATCH /api/growth/weekly-goal} 요청 본문(Issue #129).
 *
 * <p>{@code targetValue} 는 이번 주 연습 목표 횟수입니다. 프론트 {@code WEEKLY_GOAL_OPTIONS}
 * (1~7)와 같은 범위입니다. 범위 밖이거나 값이 없으면 {@code @Valid} 가 걸러
 * {@link com.cuea.common.exception.GlobalExceptionHandler} 가 {@code INVALID_REQUEST}(400)
 * 로 바꿉니다.
 *
 * <p>primitive {@code int} 로 두면 본문에 키가 없을 때 조용히 0 이 됩니다. {@code Integer} +
 * {@code @NotNull} 로 "값 없음" 을 0 과 구분해 400 으로 막습니다.
 *
 * @param targetValue 이번 주 목표 연습 횟수. 1~7
 */
public record WeeklyGoalUpdateRequest(
        @NotNull
        @Min(1)
        @Max(7)
        Integer targetValue
) {
}
