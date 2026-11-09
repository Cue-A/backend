package com.cuea.domain.calendar.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.home.service.KoreaTime;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 월별 캘린더 조회의 {@code month} 파라미터({@code YYYY-MM})를 검증하고 KST 월 경계를
 * 반열림 UTC 구간으로 바꿉니다.
 *
 * <p>경계는 홈 통계와 같은 철학입니다. KST 로 "그 달 1일 00:00" 부터 "다음 달 1일 00:00" 직전까지.
 * {@code scheduled_at}({@code timestamptz})을 그대로 비교하도록 UTC {@link OffsetDateTime} 으로
 * 넘겨 인덱스를 탑니다. 연말·연초(12월→1월)도 {@link YearMonth#plusMonths} 가 해를 넘겨 줍니다.
 *
 * @param month            원본 문자열({@code YYYY-MM})
 * @param startInclusive   그 달 1일 00:00 KST 의 UTC 시각(포함)
 * @param endExclusive     다음 달 1일 00:00 KST 의 UTC 시각(제외)
 */
public record CalendarMonth(
        String month,
        OffsetDateTime startInclusive,
        OffsetDateTime endExclusive
) {

    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    public static CalendarMonth parse(String month) {
        if (month == null || !MONTH_PATTERN.matcher(month).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "달 형식은 YYYY-MM 입니다");
        }
        YearMonth ym = YearMonth.parse(month, YEAR_MONTH);
        OffsetDateTime start = ym.atDay(1).atStartOfDay(KoreaTime.ZONE).toOffsetDateTime();
        OffsetDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay(KoreaTime.ZONE).toOffsetDateTime();
        return new CalendarMonth(month, start, end);
    }
}
