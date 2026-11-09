package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;

import java.util.List;

/**
 * 내 질문 문구 · 분류 규칙. 추가와 수정이 같은 규칙을 쓰도록 한 곳에 둡니다.
 *
 * <p>문구 길이는 <b>앞뒤 공백을 자른 뒤</b> 셉니다. 저장도 자른 값으로 합니다. 분류는 질문은행 공통 질문의 다섯
 * 가지로, 면접 질문에 AI 가 붙이는 분류(직무역량 등 8종)와는 다른 목록입니다. 보낸 글자 그대로 비교합니다.
 */
final class CustomQuestionRule {

    /** {@code common_question.text VARCHAR(200)}. 넘으면 DB 가 잘라내는 게 아니라 터집니다. */
    static final int MAX_TEXT_LENGTH = 200;

    /** {@code common_question_category_check} 와 같은 목록입니다. 바꾸면 마이그레이션도 함께 바꿉니다. */
    static final List<String> CATEGORIES = List.of("인성", "경험", "지원동기", "직무", "기타");

    private CustomQuestionRule() {
    }

    /** 규칙을 검사하고 앞뒤 공백을 자른 문구를 돌려줍니다. */
    static String text(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "text 가 필요합니다");
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "질문은 %d자 이하여야 합니다".formatted(MAX_TEXT_LENGTH));
        }
        return trimmed;
    }

    /** {@code List.of} 의 {@code contains(null)} 은 false 가 아니라 NPE 라 null 을 먼저 거릅니다. */
    static String category(String category) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "category 는 %s 중 하나여야 합니다".formatted(String.join(", ", CATEGORIES)));
        }
        return category;
    }
}
