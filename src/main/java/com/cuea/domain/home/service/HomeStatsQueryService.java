package com.cuea.domain.home.service;

import com.cuea.domain.home.dto.response.HomeStatsResponse;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 홈 연습 통계(기간 무관, Issue #113). 이번 달 연습 일수·지난달 대비 증감·스트릭을 계산합니다.
 *
 * <p>모두 <b>완료({@code COMPLETED}) 세션의 Asia/Seoul 날짜</b>로만 셉니다. 저장된 연속 일수
 * (streak 테이블)는 쓰지 않고, 완료 세션의 날짜 집합을 조회해 조회 시 계산합니다
 * (writer 가 없고 조회 계산이 더 정확 — {@code Streak} 엔티티 주석). "오늘" 은 {@link Clock}
 * 에서 KST 로 얻어 테스트가 날짜 경계를 고정할 수 있습니다.
 *
 * <p>리포지토리가 날짜 한 칸만 투영하므로 엔티티·{@code report_data}(jsonb)를 끌어오지 않습니다.
 */
@Service
@RequiredArgsConstructor
public class HomeStatsQueryService {

    private final InterviewSessionRepository sessionRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public HomeStatsResponse stats(String userId) {
        LocalDate today = KoreaTime.today(clock);

        // 완료 세션의 KST 날짜(중복 없음, 오름차순). 이번 달·스트릭 모두 이 집합에서 계산합니다.
        NavigableSet<LocalDate> activeDates = sessionRepository.findCompletedDatesInKst(userId).stream()
                .map(LocalDate::parse)
                .collect(Collectors.toCollection(TreeSet::new));

        return new HomeStatsResponse(thisMonth(activeDates, today), streak(activeDates, today));
    }

    /**
     * 이번 달(1일~오늘) 연습 일수와 지난달 같은 기간 대비 증감.
     *
     * <p>지난달은 <b>1일~"오늘과 같은 일자"</b>까지만 봅니다. 월초에 지난달 전체와 비교해 늘 모자라
     * 보이는 왜곡을 피합니다(프론트 {@code types/home.ts} {@code diffFromLastMonth} 주석과 동일 철학).
     * 지난달이 더 짧아 같은 일자가 없으면 지난달 말일까지로 clamp 합니다. 지난달 해당 구간에 기록이
     * 하나도 없으면 비교 대상이 없다는 뜻으로 {@code diffFromLastMonth} 는 null 입니다.
     */
    private static HomeStatsResponse.ThisMonth thisMonth(NavigableSet<LocalDate> activeDates, LocalDate today) {
        LocalDate thisMonthStart = today.withDayOfMonth(1);
        int activeDays = countInRange(activeDates, thisMonthStart, today);

        LocalDate lastMonthStart = thisMonthStart.minusMonths(1);
        int lastMonthEndDay = Math.min(today.getDayOfMonth(), lastMonthStart.lengthOfMonth());
        LocalDate lastMonthEnd = lastMonthStart.withDayOfMonth(lastMonthEndDay);
        boolean hasLastMonth = !activeDates.subSet(lastMonthStart, true, thisMonthStart, false).isEmpty();
        Integer diff = hasLastMonth ? activeDays - countInRange(activeDates, lastMonthStart, lastMonthEnd) : null;

        return new HomeStatsResponse.ThisMonth(today.getMonthValue(), activeDays, diff);
    }

    /** {@code [start, end]}(양 끝 포함) 구간의 날짜 수. */
    private static int countInRange(NavigableSet<LocalDate> dates, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            return 0;
        }
        return dates.subSet(start, true, end, true).size();
    }

    /**
     * 스트릭. 오늘 기록이 있으면 오늘부터, 없고 어제 기록이 있으면 어제부터 거꾸로 끊길 때까지가 current.
     * 오늘·어제 모두 없으면 0. best 는 전체 날짜 집합에서 가장 긴 연속 구간입니다. 같은 날 여러 번
     * 완료해도 날짜 집합이라 하루로 셉니다.
     */
    private static HomeStatsResponse.Streak streak(NavigableSet<LocalDate> activeDates, LocalDate today) {
        return new HomeStatsResponse.Streak(currentStreak(activeDates, today), bestStreak(activeDates));
    }

    private static int currentStreak(NavigableSet<LocalDate> activeDates, LocalDate today) {
        LocalDate cursor;
        if (activeDates.contains(today)) {
            cursor = today;
        } else if (activeDates.contains(today.minusDays(1))) {
            cursor = today.minusDays(1);
        } else {
            return 0;
        }
        int days = 0;
        while (activeDates.contains(cursor)) {
            days++;
            cursor = cursor.minusDays(1);
        }
        return days;
    }

    private static int bestStreak(NavigableSet<LocalDate> activeDates) {
        int best = 0;
        int run = 0;
        LocalDate prev = null;
        for (LocalDate date : activeDates) {
            run = (prev != null && prev.plusDays(1).equals(date)) ? run + 1 : 1;
            best = Math.max(best, run);
            prev = date;
        }
        return best;
    }
}
