package com.cuea.domain.growth.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.growth.dto.response.ReportComparisonResponse;
import com.cuea.domain.growth.service.ReportComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회차 비교는 리포트 화면 안에서 부르므로 경로는 리포트 자원 아래입니다. 세션 · 리포트 두 도메인을 함께 읽어
 * 코드는 {@code growth} 에 둡니다.
 */
@Tag(name = "리포트")
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportComparisonController {

    private final ReportComparisonService reportComparisonService;

    @Operation(
            summary = "회차 비교 · 성장 추이",
            description = """
                    같은 면접을 다시 본 회차들(1회차 + 재면접)을 이 리포트의 회차까지 비교합니다.
                    직전 회차 대비(vsPrevious), 회차별 추이(trend), 문항별 변화(byQuestion)가 들어갑니다.

                    중단된 재면접은 회차로 세지 않고, 리포트가 없거나 분석 중 · 실패인 회차는 빠집니다.
                    그래서 comparedRounds 가 [1, 3] 처럼 빌 수 있습니다. 배열 값의 n번째는 comparedRounds 의 n번째 회차입니다.
                    비교할 이전 회차가 없으면 vsPrevious 가 null 이며 오류가 아닙니다.

                    부를 때마다 AI 가 계산합니다. 리포트 상세를 먼저 그리고 이 섹션은 따로 불러 주세요.
                    아직 분석 중이면 202 REPORT_NOT_READY, 실패한 리포트면 409 REPORT_FAILED 입니다.
                    본인 리포트가 아니거나 없으면 404 REPORT_NOT_FOUND 입니다. 분당 10회로 제한합니다.""")
    @GetMapping("/{reportId}/comparison")
    @RateLimit(key = "report-comparison", limit = 10, windowSeconds = 60)
    public Result<ReportComparisonResponse> compare(@CurrentUser String userId,
                                                    @PathVariable String reportId) {
        return Result.ok(reportComparisonService.compare(userId, reportId));
    }
}
