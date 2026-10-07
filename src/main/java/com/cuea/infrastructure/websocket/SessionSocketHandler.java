package com.cuea.infrastructure.websocket;

import com.cuea.domain.interview.service.InterviewFirstQuestionCatchUp;
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
    private final InterviewFirstQuestionCatchUp catchUp;

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        String sessionId = extractSessionId(socket);
        registry.register(sessionId, socket);
        log.debug("WebSocket 연결 sessionId={} socketId={}", sessionId, socket.getId());
        catchUpFirstQuestion(sessionId, socket);
    }

    /**
     * 연결 전에 드롭됐을 수 있는 첫 질문을 이 연결에만 복구합니다. register 이후에
     * 조회해야 그 사이의 폴링 push 와 겹쳐도 유실이 없습니다. 복구 실패가 연결을 끊지
     * 않도록 예외를 흡수합니다.
     */
    private void catchUpFirstQuestion(String sessionId, WebSocketSession socket) {
        try {
            catchUp.firstQuestion(sessionId)
                    .ifPresent(fq -> pushFirstQuestionTo(socket, fq.questionId(), fq.message()));
        } catch (RuntimeException e) {
            log.warn("연결 시점 첫 질문 복구 실패 sessionId={} socketId={}",
                    sessionId, socket.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        String sessionId = extractSessionId(socket);
        registry.unregister(sessionId, socket);
        log.debug("WebSocket 종료 sessionId={} status={}", sessionId, status.getCode());
    }

    /**
     * 면접 세션에 붙은 모든 연결에 보냅니다. 끊긴 연결은 조용히 건너뜁니다.
     *
     * @return 실제로 메시지를 전송한(열려 있고 전송에 성공한) 연결 수.
     *         직렬화 실패나 수신자가 없으면 0.
     */
    public int push(String interviewSessionId, SocketMessage<?> message) {
        String payload = serialize(message);
        if (payload == null) {
            return 0;
        }
        int delivered = 0;
        for (WebSocketSession socket : registry.find(interviewSessionId)) {
            if (!socket.isOpen()) {
                continue;
            }
            try {
                synchronized (socket) {
                    socket.sendMessage(new TextMessage(payload));
                }
                delivered++;
            } catch (IOException e) {
                log.warn("WebSocket push 실패 sessionId={} socketId={}",
                        interviewSessionId, socket.getId(), e);
            }
        }
        return delivered;
    }

    /**
     * 세션의 모든 연결에 첫 질문을 보내되, 각 연결에는 같은 questionId 를 최대 한 번만
     * 보냅니다. 끊긴 연결은 건너뜁니다.
     *
     * @return 이번 호출로 실제 전송한 연결 수.
     */
    public int pushFirstQuestion(String interviewSessionId, String questionId, SocketMessage<?> message) {
        String payload = serialize(message);
        if (payload == null) {
            return 0;
        }
        int delivered = 0;
        for (WebSocketSession socket : registry.find(interviewSessionId)) {
            if (sendFirstQuestionOnce(socket, questionId, payload)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * 연결 하나에 첫 질문을 보내되, 그 연결에 같은 questionId 를 이미 보냈으면 보내지
     * 않습니다.
     *
     * @return 이번 호출로 실제 전송하면 {@code true}.
     */
    public boolean pushFirstQuestionTo(WebSocketSession socket, String questionId, SocketMessage<?> message) {
        String payload = serialize(message);
        if (payload == null) {
            return false;
        }
        return sendFirstQuestionOnce(socket, questionId, payload);
    }

    /** 첫 질문 중복 전송을 막기 위해 소켓에 기록하는 attribute key. */
    private static final String FIRST_QUESTION_SENT_ATTR = "cuea.firstQuestionSentId";

    /**
     * 첫 질문을 소켓당 최대 한 번 전송합니다.
     *
     * <p>폴링과 catch-up 두 경로가 서로 다른 스레드에서 같은 소켓에 들어올 수 있으므로,
     * "클레임 확인 → 전송 → 클레임 기록" 을 모두 같은 {@code synchronized (socket)}
     * 모니터 안에서 원자적으로 처리합니다.
     *
     * <p>클레임은 전송이 <b>성공한 뒤에만</b> 기록합니다. 전송이 실패하면 클레임이 남지
     * 않아 재연결/복구에서 다시 시도할 수 있습니다.
     */
    private boolean sendFirstQuestionOnce(WebSocketSession socket, String questionId, String payload) {
        if (!socket.isOpen()) {
            return false;
        }
        try {
            synchronized (socket) {
                if (!socket.isOpen()) {
                    return false;
                }
                Object already = socket.getAttributes().get(FIRST_QUESTION_SENT_ATTR);
                if (questionId.equals(already)) {
                    return false;
                }
                // 전송 성공 뒤에만 클레임한다. 예외가 나면 put 에 도달하지 않아 클레임이
                // 남지 않고, 이후 복구에서 다시 시도할 수 있다.
                socket.sendMessage(new TextMessage(payload));
                socket.getAttributes().put(FIRST_QUESTION_SENT_ATTR, questionId);
            }
            return true;
        } catch (IOException e) {
            log.warn("첫 질문 push 실패 socketId={}", socket.getId(), e);
            return false;
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
