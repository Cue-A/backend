package com.cuea.common.exception;

import lombok.Getter;

/**
 * 비즈니스 예외. 컨트롤러에서 try-catch 하지 말고 이걸 던지세요.
 * {@link GlobalExceptionHandler} 가 {@code Result.fail} 로 변환합니다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
