package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomQuestionRuleTest {

    @Test
    void 문구는_앞뒤_공백을_자른_값을_돌려준다() {
        assertThat(CustomQuestionRule.text("  지원 동기를 말해 주세요.  ")).isEqualTo("지원 동기를 말해 주세요.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t"})
    void 문구가_비었거나_공백뿐이면_400(String text) {
        assertInvalid(() -> CustomQuestionRule.text(text));
    }

    /** 프론트 입력창도 200자에서 막습니다. 둘 다 UTF-16 단위로 셉니다. */
    @Test
    void 문구는_자른_뒤_200자까지다() {
        assertThat(CustomQuestionRule.text(" " + "가".repeat(200) + " ")).hasSize(200);
        assertInvalid(() -> CustomQuestionRule.text("가".repeat(201)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"인성", "경험", "지원동기", "직무", "기타"})
    void 분류는_다섯_가지를_그대로_받는다(String category) {
        assertThat(CustomQuestionRule.category(category)).isEqualTo(category);
    }

    /** 면접 질문의 AI 분류(직무역량 등)는 공통 질문 분류가 아닙니다. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"직무역량", "프로젝트경험", " 인성", "기타 ", "INSIGHT"})
    void 다섯_가지가_아닌_분류는_400(String category) {
        assertInvalid(() -> CustomQuestionRule.category(category));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
