package com.cuea.infrastructure.websocket;

import com.cuea.infrastructure.websocket.message.SocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;

/**
 * {@code /ws/interviews/{sessionId}} 로 붙은 프론트에 결과를 밀어줍니다.
 *
 * <p>프론트는 이 소켓으로 받기만 합니다. 폴링은 Spring 이 합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSocketHandler extends TextWebSocketHandler {

    private final SocketSessionRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        String sessionId = extractSessionId(socket);
        registry.register(sessionId, socket);
        log.debug("WebSocket 연결 sessionId={} socketId={}", sessionId, socket.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        String sessionId = extractSessionId(socket);
        registry.unregister(sessionId, socket);
        log.debug("WebSocket 종료 sessionId={} status={}", sessionId, status.getCode());
    }

    /** 면접 세션에 붙은 모든 연결에 보냅니다. 끊긴 연결은 조용히 건너뜁니다. */
    public void push(String interviewSessionId, SocketMessage<?> message) {
        String payload = serialize(message);
        if (payload == null) {
            return;
        }
        for (WebSocketSession socket : registry.find(interviewSessionId)) {
            if (!socket.isOpen()) {
                continue;
            }
            try {
                synchronized (socket) {
                    socket.sendMessage(new TextMessage(payload));
                }
            } catch (IOException e) {
                log.warn("WebSocket push 실패 sessionId={} socketId={}",
                        interviewSessionId, socket.getId(), e);
            }
        }
    }

    private String serialize(SocketMessage<?> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("WebSocket 메시지 직렬화 실패 type={}", message.type(), e);
            return null;
        }
    }

    private String extractSessionId(WebSocketSession socket) {
        URI uri = socket.getUri();
        if (uri == null) {
            return "unknown";
        }
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
