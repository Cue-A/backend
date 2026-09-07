package com.cuea.infrastructure.websocket.message;

/**
 * 오류. type = "error"
 *
 * @param retryable    재시도해도 되는지
 * @param needsRerecord STT 실패처럼 같은 오디오로는 결과가 같은 경우. 재녹음 안내가 필요합니다
 */
public record ErrorPushMessage(
        String errorCode,
        String message,
        boolean retryable,
        boolean needsRerecord
) {

    public static SocketMessage<ErrorPushMessage> of(ErrorPushMessage payload) {
        return SocketMessage.of("error", payload);
    }
}
