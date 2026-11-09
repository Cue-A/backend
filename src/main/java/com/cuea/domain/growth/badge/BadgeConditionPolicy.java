package com.cuea.domain.growth.badge;

import java.time.LocalDate;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * 배지 획득 조건 중 <b>순수 계산</b> 부분. DB·시각과 무관해 단위 테스트로 경계를 고정합니다.
 *
 * <p>FIRST_SESSION·TEN_SESSIONS·PRESSURE_CLEAR 는 단순 비교/존재 확인이라 서비스에서 바로
 * 판정하고, 수열 계산이 필요한 STREAK_3·RISING 만 여기 둡니다.
 */
public final class BadgeConditionPolicy {

    /** STREAK_3: 서로 다른 날짜가 연속 3일 이상 있으면 획득(과거 어느 구간이든). */
    public static final int STREAK_DAYS = 3;

    /** RISING: 인접 비교가 3번 연속 증가(= 유효 점수 최소 4개)면 획득. */
    public static final int RISING_CONSECUTIVE_RISES = 3;

    private BadgeConditionPolicy() {
    }

    /**
     * KST 완료 날짜 집합에서 <b>연속 3일 이상</b> 구간이 한 번이라도 있었는지.
     *
     * <p>현재 streak 가 아니라 전체 기록을 봅니다. 한 번 3일 연속을 달성했으면 이후 끊겨도
     * 획득으로 인정합니다(영구 보유). 같은 날 여러 번 완료해도 날짜 집합이라 하루로 셉니다.
     * 월말~다음 달, 연말~연초 경계는 {@link LocalDate} 의 날짜 연속성으로 자연히 처리됩니다.
     *
     * @param completedDates 완료 세션이 있는 KST 날짜(중복 가능, 순서 무관)
     */
    public static boolean hasThreeDayStreak(List<LocalDate> completedDates) {
        if (completedDates.size() < STREAK_DAYS) {
            return false;
        }
        NavigableSet<LocalDate> dates = new TreeSet<>(completedDates);
        if (dates.size() < STREAK_DAYS) {
            return false;
        }
        int run = 1;
        LocalDate prev = null;
        for (LocalDate date : dates) {
            if (prev != null && prev.plusDays(1).equals(date)) {
                run++;
                if (run >= STREAK_DAYS) {
                    return true;
                }
            } else {
                run = 1;
            }
            prev = date;
        }
        return false;
    }

    /**
     * 유효 점수 수열에서 <b>3회 연속 엄격 증가</b>(s[i] &lt; s[i+1] 가 연속 3번)가 한 번이라도
     * 있었는지. 동점은 상승이 아니라 연속을 끊습니다. 과거 어느 구간이든 달성했으면 획득입니다.
     *
     * <p>"3회 연속 상승" = 인접 비교 3개 모두 증가 = 유효 점수 최소 4개 필요
     * (예: 60 &lt; 65 &lt; 72 &lt; 80). 3개뿐이면(비교 2개) 미획득입니다.
     *
     * @param scoresOldestFirst 세션 완료 순서(오래된 것부터)로 정렬된 유효 점수. 정렬·필터는 호출부가 합니다
     */
    public static boolean hasRisingStreak(List<Integer> scoresOldestFirst) {
        if (scoresOldestFirst.size() <= RISING_CONSECUTIVE_RISES) {
            return false;
        }
        int rises = 0;
        for (int i = 1; i < scoresOldestFirst.size(); i++) {
            if (scoresOldestFirst.get(i) > scoresOldestFirst.get(i - 1)) {
                rises++;
                if (rises >= RISING_CONSECUTIVE_RISES) {
                    return true;
                }
            } else {
                rises = 0;
            }
        }
        return false;
    }
}
