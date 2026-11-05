package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;

/**
 * 질문은행 메모 규칙.
 *
 * <p>비었거나 공백뿐이면 메모를 지웁니다. 메모만 지우는 API 가 따로 없기 때문입니다.
 * 그 밖에는 앞뒤 공백을 자르지 않고 보낸 그대로 저장합니다.
 */
final class BookmarkMemo {

    /** {@code memo VARCHAR(500)}. 넘으면 DB 가 잘라내는 게 아니라 터집니다. */
    static final int MAX_LENGTH = 500;

    private BookmarkMemo() {
    }

    /** 저장할 값을 돌려줍니다. null 이면 메모를 지웁니다. */
    static String normalize(String memo) {
        if (memo == null || memo.isBlank()) {
            return null;
        }
        if (memo.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "메모는 %d자 이하여야 합니다".formatted(MAX_LENGTH));
        }
        return memo;
    }
}
