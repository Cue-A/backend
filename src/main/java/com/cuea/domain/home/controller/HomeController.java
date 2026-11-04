package com.cuea.domain.home.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.home.dto.response.RecentReportListResponse;
import com.cuea.domain.home.service.RecentReportQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 홈 대시보드 조회 API.
 *
 * <p>지금은 "최근 리포트 요약"(Issue #107) 하나입니다. 배지·스트릭·주간 목표·일정·점수 추이는
 * 별도 도메인(growth · calendar)과 정책이 정해진 뒤 각각 추가합니다. 홈 화면 전부를 한 API 로
 * 합치지 않습니다.
 */
@Tag(name = "홈")
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class HomeController {

    private final RecentReportQueryService recentReportQueryService;

    @Operation(
            summary = "최근 리포트 요약",
            description = """
                    홈 대시보드에 보여줄 본인의 최근 리포트입니다. 최신 완료순이고 기본 3건입니다.

                    열람 가능한 리포트(COMPLETED · PARTIAL)만 나옵니다. 분석 중(PROCESSING)·실패(FAILED)·
                    리포트를 아직 요청하지 않은 세션은 빠집니다. 재연습 세션의 리포트도 열람 가능하면 포함합니다.
                    리포트가 없으면 reports 는 빈 배열입니다.

                    limit 은 1~10 이며 비우면 3 입니다. 범위를 벗어나면 400 INVALID_REQUEST 입니다.
                    각 항목의 reportId 로 리포트 상세(GET /api/reports/{reportId})로 바로 이동합니다.
                    면접 제목은 companyName 과 jobRole 로 조합합니다(회사 미선택이면 companyName 이 null).""")
    @GetMapping("/recent-reports")
    @RateLimit(key = "home-recent-reports", limit = 60, windowSeconds = 60)
    public Result<RecentReportListResponse> recentReports(
            @CurrentUser String userId,
            @RequestParam(required = false) Integer limit) {

        return Result.ok(recentReportQueryService.recentReports(userId, limit));
    }
}
