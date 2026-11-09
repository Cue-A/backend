package com.cuea.domain.growth.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.growth.dto.request.WeeklyGoalUpdateRequest;
import com.cuea.domain.growth.dto.response.GrowthOverviewResponse;
import com.cuea.domain.growth.dto.response.WeeklyGoalResponse;
import com.cuea.domain.growth.service.WeeklyGoalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 성장 관리(Issue #129). 이번 주 연습 목표 조회·수정의 단일 출처입니다.
 *
 * <p>{@code GET /api/growth} 는 성장 관리 요약(조합 응답)이며, 이 이슈에서는 {@code weeklyGoal}
 * 조각만 채웁니다({@code latestReport}·{@code badges} 는 각 이슈에서). 홈 전용 목표 API 는 따로
 * 만들지 않고, 홈 통합 응답({@code GET /api/home}, 별도 이슈)이 같은 {@link WeeklyGoalService}
 * 결과를 씁니다.
 */
@Tag(name = "성장 관리")
@RestController
@RequestMapping("/api/growth")
@RequiredArgsConstructor
public class GrowthController {

    private final WeeklyGoalService weeklyGoalService;

    @Operation(
            summary = "성장 관리 요약 조회",
            description = """
                    성장 관리 화면의 요약입니다. 이번 주 연습 목표(weeklyGoal)를 돌려줍니다.
                    목표를 아직 안 정했으면 weeklyGoal 은 null 입니다(임의 기본값을 만들지 않습니다).

                    latestReport · badges 는 각 이슈에서 채웁니다(현재는 null · 빈 배열).
                    조합 응답의 구조는 유지합니다. 로그인 필수입니다.""")
    @GetMapping
    @RateLimit(key = "growth-overview", limit = 60, windowSeconds = 60)
    public Result<GrowthOverviewResponse> overview(@CurrentUser String userId) {
        WeeklyGoalResponse weeklyGoal = weeklyGoalService.getThisWeek(userId);
        return Result.ok(GrowthOverviewResponse.ofWeeklyGoal(weeklyGoal));
    }

    @Operation(
            summary = "이번 주 목표 저장·수정",
            description = """
                    이번 주(월~일, Asia/Seoul) 연습 목표 횟수를 저장하거나 바꿉니다.
                    없으면 새로 만들고(upsert), 있으면 목표치만 바꿉니다.
                    저장 후 이번 주 완료 연습 수(doneCount)를 다시 세어 함께 돌려줍니다.

                    targetValue 는 1~7 입니다. 범위 밖이거나 값이 없으면 400 INVALID_REQUEST 입니다.
                    로그인 필수입니다. 분당 30회로 제한합니다.""")
    @PatchMapping("/weekly-goal")
    @RateLimit(key = "weekly-goal-update", limit = 30, windowSeconds = 60)
    public Result<WeeklyGoalResponse> updateWeeklyGoal(@CurrentUser String userId,
                                                       @Valid @RequestBody WeeklyGoalUpdateRequest request) {
        return Result.ok(weeklyGoalService.upsertThisWeek(userId, request.targetValue()));
    }
}
