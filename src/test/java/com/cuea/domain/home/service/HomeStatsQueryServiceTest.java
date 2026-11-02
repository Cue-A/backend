package com.cuea.domain.home.service;

import com.cuea.domain.home.dto.response.HomeStatsResponse;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 홈 연습 통계(Issue #113) 계산 로직을 Clock 고정으로 봅니다. 리포지토리는 완료 KST 날짜 목록만
 * 돌려주므로 mock 으로 주입하고, 이번 달 집계·지난달 대비 증감·스트릭 규칙을 검증합니다.
 */
class HomeStatsQueryServiceTest {

    private static final String USER = "user-1";

    /** "오늘" 을 KST 로 고정합니다. 2026-11-15 12:00 KST = 03:00Z. */
    private static Clock kstClockOn(LocalDate kstDate) {
        Instant noonKst = kstDate.atTime(12, 0).atZone(KoreaTime.ZONE).toInstant();
        return Clock.fixed(noonKst, ZoneOffset.UTC);
    }

    private HomeStatsQueryService serviceWith(Clock clock, List<LocalDate> dates) {
        InterviewSessionRepository repo = mock(InterviewSessionRepository.class);
        when(repo.findCompletedDatesInKst(anyString()))
                .thenReturn(dates.stream().map(LocalDate::toString).toList());
        return new HomeStatsQueryService(repo, clock);
    }

    @Test
    void 이번_달_연습_일수는_KST_기준_고유_날짜_수다() {
        LocalDate today = LocalDate.of(2026, 11, 15);
        HomeStatsQueryService service = serviceWith(kstClockOn(today), List.of(
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 10, 31))); // 지난달은 제외

        HomeStatsResponse.ThisMonth thisMonth = service.stats(USER).thisMonth();

        assertThat(thisMonth.month()).isEqualTo(11);
        assertThat(thisMonth.activeDays()).isEqualTo(3);
    }

    @Test
    void 지난달_같은_기간_대비_증감을_계산한다() {
        LocalDate today = LocalDate.of(2026, 11, 10); // 이번 달 1~10일 비교, 지난달도 1~10일
        HomeStatsQueryService service = serviceWith(kstClockOn(today), List.of(
                // 이번 달 1~10일 중 3일
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 9),
                // 지난달 1~10일 중 1일
                LocalDate.of(2026, 10, 3),
                // 지난달 11~31일(비교 구간 밖) — hasLastMonth 를 true 로만 만듦
                LocalDate.of(2026, 10, 20)));

        HomeStatsResponse.ThisMonth thisMonth = service.stats(USER).thisMonth();

        assertThat(thisMonth.activeDays()).isEqualTo(3);
        assertThat(thisMonth.diffFromLastMonth()).isEqualTo(2); // 3 - 1
    }

    @Test
    void 지난달_기록이_없으면_증감은_null_이다() {
        LocalDate today = LocalDate.of(2026, 11, 10);
        HomeStatsQueryService service = serviceWith(kstClockOn(today), List.of(
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5)));

        HomeStatsResponse.ThisMonth thisMonth = service.stats(USER).thisMonth();

        assertThat(thisMonth.activeDays()).isEqualTo(2);
        assertThat(thisMonth.diffFromLastMonth()).isNull();
    }

    @Test
    void 오늘_완료했으면_오늘부터_스트릭을_센다() {
        LocalDate today = LocalDate.of(2026, 11, 15);
        HomeStatsQueryService service = serviceWith(kstClockOn(today), List.of(
                LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)));

        assertThat(service.stats(USER).streak().currentDays()).isEqualTo(3);
    }

    @Test
    void 오늘은_안했지만_어제까지_이어졌으면_0_이_아니다() {
        LocalDate today = LocalDate.of(2026, 11, 15);
        HomeStatsQueryService service = serviceWith(kstClockOn(today), List.of(
                LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14)));

        // 오늘(15일) 기록 없음, 어제(14일)부터 역산 → 3
        assertThat(service.stats(USER).streak().currentDays()).isEqualTo(3);
    }

    @Test
    void 오늘도_어제도_없으면_current_는_0_이다() {
        LocalDate today = LocalDate.of(2026, 11, 15);
        HomeStatsQueryService service = serviceWith(kstClockOn(today), List.of(
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11)));

        assertThat(service.stats(USER).streak().currentDays()).isZero();
    }

    @Test
    void 중간에_하루_빠지면_스트릭이_끊긴다() {
        LocalDate today = LocalDate.of(2026, 11, 15);
        HomeStatsQueryService service = serviceWith(kstClockOn(today), List.of(
                LocalDate.of(2026, 11, 13), // 14일 빠짐
                LocalDate.of(2026, 11, 15)));

        assertThat(service.stats(USER).streak().currentDays()).isEqualTo(1); // 오늘만
    }

    @Test
    void best_는_전체에서_가장_긴_연속_구간이다() {
        LocalDate today = LocalDate.of(2026, 11, 15);
        HomeStatsQueryService service = serviceWith(kstClockOn(today), List.of(
                // 과거 5일 연속
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5),
                // 최근 2일 연속
                LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)));

        HomeStatsResponse.Streak streak = service.stats(USER).streak();
        assertThat(streak.bestDays()).isEqualTo(5);
        assertThat(streak.currentDays()).isEqualTo(2);
    }

    @Test
    void 기록이_없으면_모두_0_이고_증감은_null_이다() {
        LocalDate today = LocalDate.of(2026, 11, 15);
        HomeStatsQueryService service = serviceWith(kstClockOn(today), List.of());

        HomeStatsResponse stats = service.stats(USER);
        assertThat(stats.thisMonth().activeDays()).isZero();
        assertThat(stats.thisMonth().diffFromLastMonth()).isNull();
        assertThat(stats.streak().currentDays()).isZero();
        assertThat(stats.streak().bestDays()).isZero();
    }
}
