package com.cuea.domain.home.service;

import com.cuea.domain.home.dto.response.HomePracticeResponse;
import com.cuea.domain.home.dto.response.HomePracticeResponse.ActivityDay;
import com.cuea.domain.home.dto.response.HomePracticeResponse.ScorePoint;
import com.cuea.domain.home.dto.response.HomePracticeResponse.ScoreTrend;
import com.cuea.domain.interview.repository.DailyActivityRow;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.report.repository.MonthlyScoreRow;
import com.cuea.domain.report.repository.ScorePointRow;
import com.cuea.domain.report.repository.ScoreSummaryRow;
import com.cuea.domain.report.service.ReportSummaryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 홈 연습 기록(기간 의존, Issue #114): 잔디(활동 히트맵) · 점수 추이 · 기간 통계.
 *
 * <p>활동(잔디·총연습·연습일수)과 점수(평균·최고·추이) 모두 <b>세션(연습) 완료 시각
 * {@code session.completed_at}</b> 기준입니다(PR #116 리뷰: 재분석에도 날짜가 밀리지 않고 활동과 점수의
 * 날짜 기준이 일치). 날짜·월 경계는 모두 Asia/Seoul 로 계산하고, "오늘" 은 {@link Clock} 에서 KST 로
 * 얻습니다.
 *
 * <p>세션 집계는 면접 도메인의 {@link InterviewSessionQueryService}, 점수 집계는 리포트 도메인의
 * {@link ReportSummaryQueryService} 를 거칩니다(도메인 간 참조는 service 레벨에서만 —
 * docs/01-conventions.md). 두 창구가 PostgreSQL 집계(projection)만 돌려줘 엔티티·
 * {@code report_data}(jsonb)를 끌어오지 않습니다.
 *
 * <p>DB 에는 쉰 날 행이 없으므로 잔디의 빈 날 0 채움은 여기서(기간 전체 날짜를 돌며) 만듭니다.
 * 점수가 없는 날/달은 0 으로 만들지 않습니다 — 애초에 조회에서 빠집니다.
 */
@Service
@RequiredArgsConstructor
public class HomePracticeQueryService {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final InterviewSessionQueryService sessionQueryService;
    private final ReportSummaryQueryService reportSummaryQueryService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public HomePracticeResponse practice(String userId, PracticePeriod period) {
        LocalDate today = KoreaTime.today(clock);
        LocalDate startDate = today.minusDays(period.days() - 1L);

        // KST 기간의 반열림 UTC 경계. 시작 자정 ~ (오늘 + 1일) 자정.
        OffsetDateTime startInclusive = startDate.atStartOfDay(KoreaTime.ZONE).toOffsetDateTime();
        OffsetDateTime endExclusive = today.plusDays(1).atStartOfDay(KoreaTime.ZONE).toOffsetDateTime();

        List<ActivityDay> days = heatmap(userId, startDate, today, startInclusive, endExclusive);
        int activeDays = (int) days.stream().filter(day -> day.count() > 0).count();
        long totalCount = sessionQueryService.countCompletedPracticeInPeriod(userId, startInclusive, endExclusive);

        ScoreSummaryRow scores = reportSummaryQueryService.summarizeScores(userId, startInclusive, endExclusive);
        Integer averageScore = scores == null ? null : roundToInt(scores.average());
        Integer bestScore = scores == null ? null : scores.best();

        ScoreTrend trend = trend(userId, period, startInclusive, endExclusive);

        return new HomePracticeResponse(period.code(), startDate.format(ISO_DATE), today.format(ISO_DATE),
                days, totalCount, activeDays, averageScore, bestScore, trend);
    }

    /** 기간 모든 날을 날짜 오름차순으로. DB 에 있는 날은 그 수, 쉰 날은 0. */
    private List<ActivityDay> heatmap(String userId, LocalDate startDate, LocalDate endDate,
                                      OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
        Map<String, Long> counts = sessionQueryService
                .countDailyPracticeInKst(userId, startInclusive, endExclusive).stream()
                .collect(Collectors.toMap(DailyActivityRow::date, DailyActivityRow::count));

        List<ActivityDay> days = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            String iso = date.format(ISO_DATE);
            days.add(new ActivityDay(iso, counts.getOrDefault(iso, 0L)));
        }
        return days;
    }

    private ScoreTrend trend(String userId, PracticePeriod period,
                             OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
        return switch (period.trendUnit()) {
            case SESSION -> sessionTrend(userId, startInclusive, endExclusive);
            case MONTH -> monthlyTrend(userId, startInclusive, endExclusive);
        };
    }

    /**
     * 회차별 추이(4w·10w). 기간 안에서 점수 있는 리포트를 완료 최신순으로 최대 8개 받아 오래된 순으로
     * 뒤집습니다. 같은 날 여러 리포트도 각각 한 점이고, 완료 시각·{@code publicId} 로 정렬이 안정화됩니다.
     */
    private ScoreTrend sessionTrend(String userId, OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
        List<ScorePointRow> latestFirst = reportSummaryQueryService.findScorePointsLatestFirst(
                userId, startInclusive, endExclusive, PracticePeriod.SESSION_TREND_LIMIT);

        List<ScorePoint> points = latestFirst.stream()
                .sorted(Comparator.comparing(ScorePointRow::completedAt))
                .map(row -> {
                    LocalDate date = row.completedAt().atZone(KoreaTime.ZONE).toLocalDate();
                    return new ScorePoint(date.format(ISO_DATE), sessionLabel(date), row.score());
                })
                .toList();
        return new ScoreTrend(PracticePeriod.TrendUnit.SESSION.json(), points);
    }

    /** 월 평균 추이(6m·1y). 유효 점수가 있는 달만, 오래된 달부터. 점수 없는 달은 점 없음. */
    private ScoreTrend monthlyTrend(String userId, OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
        List<MonthlyScoreRow> rows = reportSummaryQueryService.findMonthlyScoreAverages(userId, startInclusive, endExclusive);

        List<ScorePoint> points = rows.stream()
                .map(row -> {
                    YearMonth ym = YearMonth.parse(row.yearMonth(), YEAR_MONTH);
                    return new ScorePoint(row.yearMonth(), monthLabel(ym), roundToInt(row.average()));
                })
                .toList();
        return new ScoreTrend(PracticePeriod.TrendUnit.MONTH.json(), points);
    }

    /** 평균(numeric)을 반올림 정수로. null 이면 null(유효 점수가 없다는 뜻, 0 으로 만들지 않음). */
    private static Integer roundToInt(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** "9월 28일" — 프론트가 date 로 다시 만들 수 있지만 바로 쓸 수 있게 함께 내려줍니다. */
    private static String sessionLabel(LocalDate date) {
        return "%d월 %d일".formatted(date.getMonthValue(), date.getDayOfMonth());
    }

    /** "2026년 9월" */
    private static String monthLabel(YearMonth ym) {
        return "%d년 %d월".formatted(ym.getYear(), ym.getMonthValue());
    }
}
