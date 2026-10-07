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
     * 연결 전에 드롭됐을 수 있는 첫 질문을 이 소켓에만 복구해 내려줍니다(Issue #35).
     *
     * <p><b>register 이후</b>에 조회합니다. 등록을 먼저 끝내 두면 폴링 push 가 이 소켓을
     * 찾을 수 있어 유실이 없습니다. 복구와 폴링 push 가 같은 소켓에 겹쳐도
     * {@link #pushFirstQuestionTo}/{@link #pushFirstQuestion} 가 소켓별 questionId
     * 클레임으로 <b>소켓당 한 번만</b> 보내므로 중복이 생기지 않습니다.
     *
     * <p>복구 조회·전송 실패가 연결 자체를 끊지 않도록 예외를 흡수합니다. 복구가
     * 안 되더라도 이후 정상 push 경로는 그대로 동작합니다.
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
     * 첫 질문을 세션의 모든 연결에 보내되, 각 소켓에는 <b>해당 questionId 를 한 번만</b>
     * 보냅니다(Issue #35). 폴링({@link com.cuea.domain.interview.service.InterviewFirstQuestionPoller})
     * 과 연결 시점 복구(catch-up)가 같은 소켓에 동시에 밀어도, 소켓별 클레임이 중복
     * 전송을 막습니다. 끊긴 연결은 건너뜁니다.
     *
     * @return 이번 호출로 실제 전송한 연결 수. 이미 보냈거나 수신자가 없으면 0.
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
     * 방금 연결된 소켓 <b>하나</b>에 첫 질문을 보내되, 그 소켓에 이미 같은 questionId 를
     * 보냈으면 보내지 않습니다(연결 시점 복구 전용, Issue #35).
     *
     * @return 이번 호출로 실제 전송하면 {@code true}. 이미 보냈거나 전송 불가면 {@code false}.
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
     * 소켓에 첫 질문 questionId 를 <b>claim-after-send</b> 로 한 번만 전송합니다.
     *
     * <p><b>원자성:</b> 폴링 fan-out({@link #pushFirstQuestion})과 catch-up
     * ({@link #pushFirstQuestionTo}) 두 경로가 서로 다른 스레드에서 같은 소켓에 동시에
     * 들어올 수 있습니다. 두 경로 모두 이 메서드를 통과하고, "클레임 확인 → 전송 →
     * 클레임 기록" 을 전부 <b>동일한 {@code synchronized (socket)} 모니터</b> 안에서
     * 수행합니다. 즉 중복 방지는 <b>우리가 쥔 이 모니터 하나</b>로만 보장하며, Spring 의
     * {@code sendMessage} 내부 lock 구현에 의존하지 않습니다.
     *
     * <p><b>실패 semantics(claim-after-send):</b> 클레임은 {@code sendMessage} 가
     * <b>성공한 뒤에만</b> 기록합니다. 전송이 {@link IOException} 으로 실패하면 클레임을
     * 남기지 않고 {@code false} 를 돌려주므로, 같은 소켓이나 재연결에서 다시 복구를
     * 시도할 수 있습니다(실패한 전송을 "전달 완료" 로 고착시키지 않음).
     *
     * <p><b>보장 범위:</b> 여기서 보장하는 것은 "동일 {@link WebSocketSession} 에 대해
     * Backend 의 첫 질문 {@code sendMessage} 는 최대 1회" 입니다. 프론트 애플리케이션이
     * 실제로 처리했다는 ACK 가 아니므로 end-to-end exactly-once 는 아닙니다.
     *
     * <p>클레임은 소켓 attribute 에 저장되므로 연결이 끊기면 소켓과 함께 사라집니다
     * (별도 cleanup 불필요).
     *
     * @return 이번 호출에서 전송에 성공해 새로 클레임했으면 {@code true}. 이미 보냈거나
     *         소켓이 닫혔거나 전송에 실패했으면 {@code false}.
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
                    // 이 소켓에는 이미 이 첫 질문을 보냈습니다(폴링/복구 중복 방지).
                    return false;
                }
                // 전송이 성공해야만 아래에서 클레임을 기록합니다. sendMessage 가 예외를
                // 던지면 put 에 도달하지 않아 클레임이 남지 않고, catch 로 내려가 false 를
                // 돌려줍니다(실패한 전송을 "전달 완료" 로 고착시키지 않음).
                socket.sendMessage(new TextMessage(payload));
                socket.getAttributes().put(FIRST_QUESTION_SENT_ATTR, questionId);
            }
            return true;
        } catch (IOException e) {
            // 전송 실패: 클레임을 남기지 않았으므로 다음 연결/복구에서 다시 시도할 수 있다.
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
