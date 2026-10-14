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
 * {@code /ws/reports/{reportId}} 로 붙은 프론트에 리포트 진행·결과를 밀어줍니다.
 *
 * <p>면접 소켓({@code /ws/interviews/{sessionId}})과 따로 둡니다. 리포트는 면접이
 * 끝난 뒤 만들어지므로 면접 소켓은 이미 닫혀 있을 가능성이 높습니다.
 *
 * <p>연결 목록은 면접 소켓과 섞이지 않게 자기 레지스트리를 따로 들고 있습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportSocketHandler extends TextWebSocketHandler {

    private final SocketSessionRegistry registry = new SocketSessionRegistry();
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        registry.register(extractReportId(socket), socket);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        registry.unregister(extractReportId(socket), socket);
    }

    /**
     * 리포트에 붙은 모든 연결에 보냅니다. 끊긴 연결은 조용히 건너뜁니다.
     *
     * @return 실제로 보낸 연결 수. 수신자가 없으면 0
     */
    public int push(String reportId, SocketMessage<?> message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("WebSocket 메시지 직렬화 실패 type={}", message.type(), e);
            return 0;
        }

        int delivered = 0;
        for (WebSocketSession socket : registry.find(reportId)) {
            if (!socket.isOpen()) {
                continue;
            }
            try {
                synchronized (socket) {
                    socket.sendMessage(new TextMessage(payload));
                }
                delivered++;
            } catch (IOException e) {
                log.warn("리포트 WebSocket push 실패 reportId={} socketId={}", reportId, socket.getId(), e);
            }
        }
        return delivered;
    }

    private String extractReportId(WebSocketSession socket) {
        URI uri = socket.getUri();
        if (uri == null) {
            return "unknown";
        }
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
