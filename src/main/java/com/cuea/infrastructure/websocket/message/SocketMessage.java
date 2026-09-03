package com.cuea.infrastructure.websocket.message;

/**
 * 프론트로 나가는 WebSocket 메시지의 공통 봉투.
 * 스키마는 백엔드가 정합니다. AI 응답 형식을 그대로 쓰지 않습니다.
 */
public record SocketMessage<T>(String type, T payload) {

    public static <T> SocketMessage<T> of(String type, T payload) {
        return new SocketMessage<>(type, payload);
    }
}
