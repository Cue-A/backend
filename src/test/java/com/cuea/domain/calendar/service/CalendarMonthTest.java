package com.cuea.domain.calendar.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** month 파라미터 검증과 KST 월 경계(UTC 반열림 구간) 계산. */
class CalendarMonthTest {

    @Test
    void 월_경계는_KST_자정을_UTC_로_환산한다() {
        CalendarMonth month = CalendarMonth.parse("2026-08");

        // 2026-08-01 00:00 KST = 2026-07-31 15:00 UTC
        assertThat(month.startInclusive()).isEqualTo(OffsetDateTime.parse("2026-07-31T15:00:00+00:00"));
        // 2026-09-01 00:00 KST = 2026-08-31 15:00 UTC
        assertThat(month.endExclusive()).isEqualTo(OffsetDateTime.parse("2026-08-31T15:00:00+00:00"));
        assertThat(month.month()).isEqualTo("2026-08");
    }

    @Test
    void 연말_12월은_다음해_1월로_넘어간다() {
        CalendarMonth month = CalendarMonth.parse("2026-12");

        assertThat(month.startInclusive()).isEqualTo(OffsetDateTime.parse("2026-11-30T15:00:00+00:00"));
        // 2027-01-01 00:00 KST = 2026-12-31 15:00 UTC
        assertThat(month.endExclusive()).isEqualTo(OffsetDateTime.parse("2026-12-31T15:00:00+00:00"));
    }

    @Test
    void 형식이_아니면_INVALID_REQUEST() {
        assertInvalid("2026-13");
        assertInvalid("2026-00");
        assertInvalid("2026-8");
        assertInvalid("26-08");
        assertInvalid("2026/08");
        assertInvalid("");
        assertInvalid(null);
    }

    @Test
    void 경계_월_01_과_12_는_허용한다() {
        assertThat(CalendarMonth.parse("2026-01").month()).isEqualTo("2026-01");
        assertThat(CalendarMonth.parse("2026-12").month()).isEqualTo("2026-12");
    }

    private static void assertInvalid(String month) {
        assertThatThrownBy(() -> CalendarMonth.parse(month))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
