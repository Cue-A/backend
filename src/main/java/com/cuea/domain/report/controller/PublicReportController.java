package com.cuea.domain.report.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.PublicApi;
import com.cuea.domain.report.dto.response.PublicReportResponse;
import com.cuea.domain.report.service.ReportQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공유 링크로 여는 리포트. 로그인 없이 부릅니다.
 *
 * <p>본인 리포트 API({@link ReportController})와 경로를 나눈 이유는 {@code JwtAuthFilter} 가
 * {@code /api/public/} 을 통째로 건너뛰게 하려는 것입니다. 링크 발급 · 해제는 본인 API 쪽에 있습니다.
 */
@Tag(name = "공개 리포트")
@PublicApi("공유 링크는 로그인하지 않은 사람에게 리포트를 보여주려는 것입니다. "
        + "소유자가 발급한 공유 토큰으로만 찾고, 해제하면 바로 404 입니다.")
@RestController
@RequestMapping("/api/public/reports")
@RequiredArgsConstructor
public class PublicReportController {

    private final ReportQueryService reportQueryService;

    @Operation(
            summary = "공개 리포트 조회",
            description = """
                    공유 링크로 리포트를 엽니다. 로그인하지 않아도 됩니다.
                    본인 리포트 화면과 같은 내용(상세 · 타임라인 · 개선 답변)을 한 번에 돌려줍니다.
                    reportId · sessionId · retry · share 는 들어가지 않아 재연습 · 재시도 · 공유 버튼은 그릴 수 없습니다.
                    녹음 · 영상 다시보기와 회차 비교도 들어가지 않습니다.

                    없는 링크 · 해제된 링크 · UUID 가 아닌 값은 모두 404 SHARED_REPORT_NOT_FOUND 입니다.
                    IP 기준 분당 60회로 제한합니다.""")
    @GetMapping("/{shareId}")
    @RateLimit(key = "public-report", limit = 60, windowSeconds = 60)
    public Result<PublicReportResponse> get(@PathVariable String shareId) {
        return Result.ok(reportQueryService.getShared(shareId));
    }
}
