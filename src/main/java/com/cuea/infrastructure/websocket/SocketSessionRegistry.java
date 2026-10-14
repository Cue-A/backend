package com.cuea.infrastructure.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 면접 세션 ID → 연결된 WebSocket 들.
 *
 * <p>한 사용자가 탭을 두 개 열 수 있으므로 세션당 여러 연결을 담습니다.
 * 단일 인스턴스 기준입니다. 서버를 여러 대로 늘리면 Redis Pub/Sub 이 필요합니다.
 */
@Component
public class SocketSessionRegistry {

    private final Map<String, Map<String, WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void register(String interviewSessionId, WebSocketSession socket) {
        sessions.computeIfAbsent(interviewSessionId, key -> new ConcurrentHashMap<>())
                .put(socket.getId(), socket);
    }

    public void unregister(String interviewSessionId, WebSocketSession socket) {
        Optional.ofNullable(sessions.get(interviewSessionId))
                .ifPresent(map -> {
                    map.remove(socket.getId());
                    if (map.isEmpty()) {
                        sessions.remove(interviewSessionId);
                    }
                });
    }

    public Collection<WebSocketSession> find(String interviewSessionId) {
        Map<String, WebSocketSession> map = sessions.get(interviewSessionId);
        return map == null ? List.of() : map.values();
    }
}
