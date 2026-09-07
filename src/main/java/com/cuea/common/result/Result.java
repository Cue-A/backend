package com.cuea.common.result;

import com.cuea.common.exception.ErrorCode;

/**
 * 모든 API 응답을 감싸는 통일 포맷.
 *
 * <pre>
 * 성공  { "success": true,  "data": { ... }, "errorCode": null, "message": null }
 * 실패  { "success": false, "data": null,    "errorCode": "SESSION_NOT_FOUND", "message": "..." }
 * </pre>
 */
public record Result<T>(
        boolean success,
        T data,
        String errorCode,
        String message
) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(true, data, null, null);
    }

    public static Result<Void> ok() {
        return new Result<>(true, null, null, null);
    }

    public static <T> Result<T> fail(String code, String message) {
        return new Result<>(false, null, code, message);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.name(), errorCode.getMessage());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode.name(), message);
    }
}
