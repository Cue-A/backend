package com.cuea.domain.document.entity;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;

import java.util.Locale;

/**
 * 업로드를 허용하는 파일 형식.
 *
 * <p>여기에 없는 확장자는 {@code FileValidator} 에서 걸러지므로, 이 enum 이 곧
 * 허용 목록입니다. 형식을 늘리려면 {@code FileValidator.DOCUMENT_TYPES} 의
 * MIME 매핑도 함께 추가해야 합니다. 한쪽만 고치면 확장자는 통과하는데 MIME 이
 * 막히거나 그 반대가 됩니다.
 */
public enum FileFormat {

    PDF,
    DOCX,
    TXT;

    /** 소문자 확장자를 형식으로 바꿉니다. 검증을 통과한 확장자만 넘어옵니다. */
    public static FileFormat fromExtension(String extension) {
        try {
            return valueOf(extension.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
    }
}
