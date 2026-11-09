package com.cuea.domain.growth.badge;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STREAK_3·RISING 의 순수 계산 경계를 DB 없이 고정합니다(Issue #131).
 */
class BadgeConditionPolicyTest {

    /* ---------------- STREAK_3 ---------------- */

    @Test
    void 연속_3일이면_스트릭_획득() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4));
        assertThat(BadgeConditionPolicy.hasThreeDayStreak(dates)).isTrue();
    }

    @Test
    void 하루_걸러_2일_2일이면_미획득() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5));
        assertThat(BadgeConditionPolicy.hasThreeDayStreak(dates)).isFalse();
    }

    @Test
    void 같은_날_3회여도_날짜는_하루라_미획득() {
        LocalDate day = LocalDate.of(2026, 11, 2);
        assertThat(BadgeConditionPolicy.hasThreeDayStreak(List.of(day, day, day))).isFalse();
    }

    @Test
    void 월말에서_다음달_초로_넘어가는_연속3일_획득() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
        assertThat(BadgeConditionPolicy.hasThreeDayStreak(dates)).isTrue();
    }

    @Test
    void 연말에서_연초로_넘어가는_연속3일_획득() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        assertThat(BadgeConditionPolicy.hasThreeDayStreak(dates)).isTrue();
    }

    @Test
    void 과거에_3일연속_했으면_현재_끊겨도_획득_유지() {
        // 과거 연속 3일 + 이후 공백 + 단발 하루
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 3),
                LocalDate.of(2026, 6, 20));
        assertThat(BadgeConditionPolicy.hasThreeDayStreak(dates)).isTrue();
    }

    @Test
    void 이틀_연속뿐이면_미획득() {
        List<LocalDate> dates = List.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3));
        assertThat(BadgeConditionPolicy.hasThreeDayStreak(dates)).isFalse();
    }

    /* ---------------- RISING ---------------- */

    @Test
    void 네_점수가_연속_상승하면_획득() {
        assertThat(BadgeConditionPolicy.hasRisingStreak(List.of(60, 65, 72, 80))).isTrue();
    }

    @Test
    void 동점이_끼면_미획득() {
        assertThat(BadgeConditionPolicy.hasRisingStreak(List.of(60, 65, 65, 80))).isFalse();
    }

    @Test
    void 중간_하락이_있으면_미획득() {
        assertThat(BadgeConditionPolicy.hasRisingStreak(List.of(80, 70, 75, 90))).isFalse();
    }

    @Test
    void 유효점수가_3개뿐이면_미획득() {
        // 3회 연속 상승은 비교 3개 = 점수 최소 4개 필요
        assertThat(BadgeConditionPolicy.hasRisingStreak(List.of(60, 70, 80))).isFalse();
    }

    @Test
    void 앞에서_끊겼다_뒤에서_연속4개_상승하면_획득() {
        // 50,40(하락) 뒤에 55<60<70<85 가 연속 상승
        assertThat(BadgeConditionPolicy.hasRisingStreak(List.of(50, 40, 55, 60, 70, 85))).isTrue();
    }

    @Test
    void 과거에_연속상승_뒤_하락해도_획득_유지() {
        // 60<65<72<80 (획득 확정) 이후 하락
        assertThat(BadgeConditionPolicy.hasRisingStreak(List.of(60, 65, 72, 80, 50, 40))).isTrue();
    }

    @Test
    void 빈_리스트는_미획득() {
        assertThat(BadgeConditionPolicy.hasRisingStreak(List.of())).isFalse();
    }
}
