package com.cuea.domain.home.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PracticePeriodTest {

    @Test
    void 비우면_기본값_4w_이다() {
        assertThat(PracticePeriod.from(null)).isEqualTo(PracticePeriod.FOUR_WEEKS);
        assertThat(PracticePeriod.from("")).isEqualTo(PracticePeriod.FOUR_WEEKS);
        assertThat(PracticePeriod.from("  ")).isEqualTo(PracticePeriod.FOUR_WEEKS);
        assertThat(PracticePeriod.DEFAULT).isEqualTo(PracticePeriod.FOUR_WEEKS);
    }

    @Test
    void 허용된_네_값을_대소문자_없이_받는다() {
        assertThat(PracticePeriod.from("4w")).isEqualTo(PracticePeriod.FOUR_WEEKS);
        assertThat(PracticePeriod.from("10w")).isEqualTo(PracticePeriod.TEN_WEEKS);
        assertThat(PracticePeriod.from("6M")).isEqualTo(PracticePeriod.SIX_MONTHS);
        assertThat(PracticePeriod.from("1Y")).isEqualTo(PracticePeriod.ONE_YEAR);
    }

    @Test
    void 일수와_추이_단위가_프론트와_일치한다() {
        assertThat(PracticePeriod.FOUR_WEEKS.days()).isEqualTo(28);
        assertThat(PracticePeriod.TEN_WEEKS.days()).isEqualTo(70);
        assertThat(PracticePeriod.SIX_MONTHS.days()).isEqualTo(182);
        assertThat(PracticePeriod.ONE_YEAR.days()).isEqualTo(364);

        assertThat(PracticePeriod.FOUR_WEEKS.trendUnit()).isEqualTo(PracticePeriod.TrendUnit.SESSION);
        assertThat(PracticePeriod.TEN_WEEKS.trendUnit()).isEqualTo(PracticePeriod.TrendUnit.SESSION);
        assertThat(PracticePeriod.SIX_MONTHS.trendUnit()).isEqualTo(PracticePeriod.TrendUnit.MONTH);
        assertThat(PracticePeriod.ONE_YEAR.trendUnit()).isEqualTo(PracticePeriod.TrendUnit.MONTH);
    }

    @Test
    void 허용하지_않는_값은_INVALID_REQUEST_400_이다() {
        assertThatThrownBy(() -> PracticePeriod.from("3w"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> PracticePeriod.from("week"))
                .isInstanceOf(BusinessException.class);
    }
}
