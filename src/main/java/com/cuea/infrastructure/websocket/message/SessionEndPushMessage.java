package com.cuea.infrastructure.websocket.message;

/**
 * 세션 종료. type = "session_end"
 *
 * @param totalQuestions 되묻기를 제외한 실제 질문 수
 */
public record SessionEndPushMessage(String sessionId, Integer totalQuestions) {

    public static SocketMessage<SessionEndPushMessage> of(String sessionId, Integer totalQuestions) {
        return SocketMessage.of("session_end",
                new SessionEndPushMessage(sessionId, totalQuestions));
    }
}
