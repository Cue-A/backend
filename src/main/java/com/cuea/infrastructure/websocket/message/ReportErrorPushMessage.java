package com.cuea.infrastructure.websocket.message;

/**
 * 리포트 생성 실패. type = "error"
 *
 * <p>면접용 {@link ErrorPushMessage} 와 달리 {@code needsRerecord} 가 없습니다.
 * 리포트는 면접이 끝난 뒤라 다시 녹음할 수 없습니다.
 *
 * @param retryable true 면 등록 API 로 다시 요청해도 됩니다
 */
public record ReportErrorPushMessage(String errorCode, String message, boolean retryable) {

    public static SocketMessage<ReportErrorPushMessage> of(ReportErrorPushMessage payload) {
        return SocketMessage.of("error", payload);
    }
}
