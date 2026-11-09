package com.cuea.domain.growth.service;

import com.cuea.domain.home.service.KoreaTime;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * 주간 목표의 "이번 주"(Asia/Seoul, 월요일~일요일)를 한 곳에서 셈합니다(Issue #129).
 *
 * <p>목표 행의 주 식별자는 {@code startDate}(그 주 월요일)와 {@code endDate}(그 주 일요일)입니다.
 * 실적({@code doneCount}) 집계는 세션 {@code completed_at}(timestamptz) 을 비교하므로, 월요일
 * 00:00 KST 부터 <b>다음 주 월요일 00:00 KST 직전</b>까지의 반열림 UTC 구간
 * {@code [startInclusive, endExclusive)} 을 함께 제공합니다. 홈 통계
 * ({@code HomePracticeQueryService}) 와 동일한 반열림·KST 경계 규약입니다.
 *
 * <p>"오늘" 을 {@link Clock} 에서 KST 로 얻으므로 테스트가 고정 시각({@code Clock.fixed})으로
 * 월·일 경계와 연말·연초 주를 안정적으로 검증할 수 있습니다.
 *
 * @param startDate      그 주 월요일 (KST). {@code goal.start_date}
 * @param endDate        그 주 일요일 (KST). {@code goal.end_date}
 * @param startInclusive 월요일 00:00 KST 의 UTC 시각 (포함). 실적 집계 하한
 * @param endExclusive   다음 주 월요일 00:00 KST 의 UTC 시각 (제외). 실적 집계 상한
 */
public record WeeklyGoalRange(
        LocalDate startDate,
        LocalDate endDate,
        OffsetDateTime startInclusive,
        OffsetDateTime endExclusive
) {

    /** 주입된 시계 기준 "이번 주"(Asia/Seoul, 월~일). */
    public static WeeklyGoalRange thisWeek(Clock clock) {
        return of(KoreaTime.today(clock));
    }

    /** 주어진 KST 날짜가 속한 주(월~일). 테스트가 임의 날짜의 주를 만들 때도 씁니다. */
    public static WeeklyGoalRange of(LocalDate kstDate) {
        LocalDate monday = kstDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        OffsetDateTime startInclusive = monday.atStartOfDay(KoreaTime.ZONE).toOffsetDateTime();
        OffsetDateTime endExclusive = monday.plusDays(7).atStartOfDay(KoreaTime.ZONE).toOffsetDateTime();
        return new WeeklyGoalRange(monday, sunday, startInclusive, endExclusive);
    }
}
