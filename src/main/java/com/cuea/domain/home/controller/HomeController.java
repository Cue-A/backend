package com.cuea.domain.home.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.home.dto.response.HomePracticeResponse;
import com.cuea.domain.home.dto.response.HomeStatsResponse;
import com.cuea.domain.home.dto.response.RecentReportListResponse;
import com.cuea.domain.home.service.HomePracticeQueryService;
import com.cuea.domain.home.service.HomeStatsQueryService;
import com.cuea.domain.home.service.PracticePeriod;
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
 * <p>영역마다 엔드포인트를 나눕니다. 홈 화면 전부를 한 API 로 합치지 않습니다.
 * <ul>
 *   <li>{@code GET /recent-reports} — 최근 리포트 요약(Issue #107)</li>
 *   <li>{@code GET /stats} — 연습 통계: 이번 달 연습 일수·스트릭(기간 무관, Issue #113)</li>
 *   <li>{@code GET /practice} — 활동 히트맵·점수 추이·기간 통계(기간 의존, Issue #114)</li>
 * </ul>
 * 배지·주간 목표·일정은 정책이 정해진 뒤 별도 도메인(growth · calendar)에서 추가합니다.
 */
@Tag(name = "홈")
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class HomeController {

    private final RecentReportQueryService recentReportQueryService;
    private final HomeStatsQueryService homeStatsQueryService;
    private final HomePracticeQueryService homePracticeQueryService;

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

    @Operation(
            summary = "홈 연습 통계 (기간 무관)",
            description = """
                    홈 상단 카드 중 기간과 상관없는 둘입니다.

                    thisMonth: 이번 달(1일~오늘, Asia/Seoul) 연습한 날 수(activeDays)와 지난달 같은 기간 대비
                    증감(diffFromLastMonth). 같은 날 여러 번 완료해도 하루로 셉니다. 지난달 해당 기간 기록이 없으면
                    diffFromLastMonth 는 null 입니다.

                    streak: 완료 세션 기록으로 계산한 연속 학습 일수입니다. currentDays 는 오늘(기록 있으면)·없으면
                    어제부터 거꾸로 센 연속 일수이고, bestDays 는 역대 최고입니다. 완료(COMPLETED) 세션만 집계하고
                    재연습도 포함합니다. 기록이 없으면 모두 0 입니다.""")
    @GetMapping("/stats")
    @RateLimit(key = "home-stats", limit = 60, windowSeconds = 60)
    public Result<HomeStatsResponse> stats(@CurrentUser String userId) {
        return Result.ok(homeStatsQueryService.stats(userId));
    }

    @Operation(
            summary = "홈 연습 기록 (기간 의존): 활동 히트맵·점수 추이·기간 통계",
            description = """
                    기간 탭에 따라 바뀌는 잔디(활동 히트맵)·점수 추이·기간 통계입니다.

                    period 는 4w(28일)·10w(70일)·6m(182일)·1y(364일) 이며 비우면 4w 입니다. 허용하지 않는 값이면
                    400 INVALID_REQUEST 입니다. 기간은 오늘을 포함해 거꾸로 센 날 수입니다(Asia/Seoul).

                    days 는 기간의 모든 날이고 쉰 날은 count=0 입니다(색·단계는 프론트가 계산). totalCount·activeDays 는
                    완료(COMPLETED) 세션 기준이며 재연습을 포함합니다. averageScore·bestScore 는 열람 가능하고 점수가
                    있는(COMPLETED·PARTIAL + scoreTotal 존재) 리포트만 집계하고, 유효 점수가 없으면 null 입니다(0 아님).

                    trend 는 4w·10w 에서 회차별(unit=session, 최근 8개), 6m·1y 에서 월 평균(unit=month)입니다. 점수 없는
                    날·달은 점으로 넣지 않습니다. 각 점은 원본 date(YYYY-MM-DD 또는 YYYY-MM)와 한글 label 을 함께 줍니다.""")
    @GetMapping("/practice")
    @RateLimit(key = "home-practice", limit = 60, windowSeconds = 60)
    public Result<HomePracticeResponse> practice(
            @CurrentUser String userId,
            @RequestParam(required = false) String period) {

        return Result.ok(homePracticeQueryService.practice(userId, PracticePeriod.from(period)));
    }
}
