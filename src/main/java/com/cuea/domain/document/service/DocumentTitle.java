package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;

/**
 * 문서 제목 규칙. 등록과 수정이 같은 규칙을 쓰도록 한 곳에 둡니다.
 *
 * <p>길이는 <b>앞뒤 공백을 자른 뒤</b> 셉니다. 저장도 자른 값으로 합니다.
 */
final class DocumentTitle {

    /** {@code doc_title VARCHAR(100)}. 넘으면 DB 가 잘라내는 게 아니라 터집니다. */
    static final int MAX_LENGTH = 100;

    private DocumentTitle() {
    }

    static void validate(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "title 이 필요합니다");
        }
        if (title.trim().length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "제목은 %d자 이하여야 합니다".formatted(MAX_LENGTH));
        }
    }
}
