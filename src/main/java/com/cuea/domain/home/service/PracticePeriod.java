package com.cuea.domain.home.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;

import java.util.Arrays;

/**
 * 홈 연습 기록 카드의 기간 탭(Issue #114). 값·일수는 프론트
 * ({@code frontend} {@code src/domain/home/lib/practicePeriod.ts})와 1:1 입니다.
 *
 * <p>기간은 달력 주/월이 아니라 <b>오늘을 포함해 거꾸로 센 날 수</b>입니다(프론트 주석과 동일).
 * 즉 {@code endDate = 오늘}, {@code startDate = 오늘 - (days - 1)}.
 *
 * <p>점수 추이 표현이 기간에 따라 갈립니다. 4주·10주는 회차가 적어 <b>회차별</b>({@code SESSION}),
 * 6개월·1년은 회차가 많아 <b>월 평균</b>({@code MONTH})으로 찍습니다.
 */
public enum PracticePeriod {

    FOUR_WEEKS("4w", 28, TrendUnit.SESSION),
    TEN_WEEKS("10w", 70, TrendUnit.SESSION),
    SIX_MONTHS("6m", 182, TrendUnit.MONTH),
    ONE_YEAR("1y", 364, TrendUnit.MONTH);

    /** 기간을 생략하면 쓰는 기본값(Issue #114: 4w). */
    public static final PracticePeriod DEFAULT = FOUR_WEEKS;

    /** 회차별 추이에서 찍는 최대 점 수. 프론트 시안의 "8회 전 ~ 최근". */
    public static final int SESSION_TREND_LIMIT = 8;

    private final String code;
    private final int days;
    private final TrendUnit trendUnit;

    PracticePeriod(String code, int days, TrendUnit trendUnit) {
        this.code = code;
        this.days = days;
        this.trendUnit = trendUnit;
    }

    public String code() {
        return code;
    }

    public int days() {
        return days;
    }

    public TrendUnit trendUnit() {
        return trendUnit;
    }

    /**
     * 쿼리 파라미터 문자열을 enum 으로. 비우면 {@link #DEFAULT}, 허용하지 않는 값이면
     * {@link ErrorCode#INVALID_REQUEST}(400). 홈 탭은 고정 네 값이라 잘못된 값을 기본값으로
     * 되돌리지 않고 400 으로 막습니다(호출 쪽 버그로 간주).
     */
    public static PracticePeriod from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(period -> period.code.equalsIgnoreCase(raw))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "period 는 4w, 10w, 6m, 1y 중 하나여야 합니다"));
    }

    /** 점수 추이 단위. 응답 {@code trend.unit} 의 소문자 값과 매핑됩니다. */
    public enum TrendUnit {
        SESSION("session"),
        MONTH("month");

        private final String json;

        TrendUnit(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }
    }
}
