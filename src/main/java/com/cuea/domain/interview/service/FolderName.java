package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;

/**
 * 폴더 이름 규칙. 만들기와 이름 바꾸기가 같은 규칙을 쓰도록 한 곳에 둡니다.
 *
 * <p>길이는 <b>앞뒤 공백을 자른 뒤</b> 셉니다. 저장과 중복 비교도 자른 값으로 합니다.
 */
final class FolderName {

    /** {@code folder_name VARCHAR(50)}. 넘으면 DB 가 잘라내는 게 아니라 터집니다. */
    static final int MAX_LENGTH = 50;

    private FolderName() {
    }

    /** 규칙을 검사하고 앞뒤 공백을 자른 이름을 돌려줍니다. */
    static String normalize(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "folderName 이 필요합니다");
        }
        String trimmed = folderName.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "폴더 이름은 %d자 이하여야 합니다".formatted(MAX_LENGTH));
        }
        return trimmed;
    }
}
