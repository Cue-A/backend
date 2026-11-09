package com.cuea.domain.growth.service;

import com.cuea.domain.home.service.KoreaTime;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 경계(월~일, KST)와 실적 집계용 반열림 UTC 구간을 검증합니다. 날짜 계산만 하는 순수 유닛이라
 * 컨테이너 없이 빠르게 돕니다. KST 경계의 DB 레벨 정확성은 Testcontainers 통합 테스트에서 따로 봅니다.
 */
class WeeklyGoalRangeTest {

    @Test
    void 주중_날짜는_그_주_월요일부터_일요일까지를_만든다() {
        // 2026-11-06 은 금요일. 그 주 월요일은 11-02, 일요일은 11-08.
        WeeklyGoalRange week = WeeklyGoalRange.of(LocalDate.of(2026, 11, 6));

        assertThat(week.startDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(week.endDate()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void 월요일은_그_날이_시작이다() {
        WeeklyGoalRange week = WeeklyGoalRange.of(LocalDate.of(2026, 11, 2)); // 월요일

        assertThat(week.startDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(week.endDate()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void 일요일은_그_주_월요일로_내려가고_그_날이_끝이다() {
        WeeklyGoalRange week = WeeklyGoalRange.of(LocalDate.of(2026, 11, 8)); // 일요일

        assertThat(week.startDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(week.endDate()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void 반열림_UTC_구간은_월요일_자정KST_부터_다음주_월요일_자정KST_직전이다() {
        WeeklyGoalRange week = WeeklyGoalRange.of(LocalDate.of(2026, 11, 6));

        // 2026-11-02 00:00 KST = 2026-11-01T15:00Z, 다음 월요일 11-09 00:00 KST = 11-08T15:00Z
        assertThat(week.startInclusive().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-01T15:00:00Z").toInstant());
        assertThat(week.endExclusive().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-08T15:00:00Z").toInstant());
    }

    @Test
    void 연말_연초를_걸치는_주도_월요일_기준으로_이어진다() {
        // 2027-01-01 은 금요일. 그 주 월요일은 2026-12-28, 일요일은 2027-01-03.
        WeeklyGoalRange week = WeeklyGoalRange.of(LocalDate.of(2027, 1, 1));

        assertThat(week.startDate()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(week.endDate()).isEqualTo(LocalDate.of(2027, 1, 3));
        assertThat(week.startInclusive().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-12-27T15:00:00Z").toInstant());
        assertThat(week.endExclusive().toInstant())
                .isEqualTo(OffsetDateTime.parse("2027-01-03T15:00:00Z").toInstant());
    }

    @Test
    void thisWeek_는_주입된_시계의_KST_오늘이_속한_주를_만든다() {
        // 2026-11-08 23:00 UTC = 2026-11-09 08:00 KST(월요일). KST 로 "오늘"=11-09 월요일.
        Instant utc = Instant.parse("2026-11-08T23:00:00Z");
        WeeklyGoalRange week = WeeklyGoalRange.thisWeek(Clock.fixed(utc, ZoneOffset.UTC));

        assertThat(KoreaTime.today(Clock.fixed(utc, ZoneOffset.UTC)))
                .isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(week.startDate()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(week.endDate()).isEqualTo(LocalDate.of(2026, 11, 15));
    }
}
