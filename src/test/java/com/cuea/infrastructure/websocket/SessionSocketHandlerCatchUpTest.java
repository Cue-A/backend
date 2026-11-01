package com.cuea.infrastructure.websocket;

import com.cuea.domain.interview.service.InterviewSocketConnectedEvent;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import com.cuea.infrastructure.websocket.message.SocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket 핸들러의 두 책임을 검증합니다.
 * <ul>
 *   <li>연결되면 등록하고 연결 이벤트를 발행한다(복구 판단은 도메인 리스너가 맡는다).</li>
 *   <li>첫 질문은 소켓당 최대 한 번만 전송하고, 전송 실패 시 claim 을 남기지 않는다.</li>
 * </ul>
 */
class SessionSocketHandlerCatchUpTest {

    private static final String SESSION_ID = "sess_1";
    private static final String QUESTION_ID = "q_1";

    private SocketSessionRegistry registry;
    private ApplicationEventPublisher eventPublisher;
    private SessionSocketHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SocketSessionRegistry();
        eventPublisher = mock(ApplicationEventPublisher.class);
        handler = new SessionSocketHandler(registry, new ObjectMapper(), eventPublisher);
    }

    /**
     * production 경로가 session attribute 에 claim 을 기록하므로 mutable·thread-safe
     * attribute map 을 쥐여 준다.
     */
    private WebSocketSession openSocket(String id) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/ws/interviews/" + SESSION_ID));
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return socket;
    }

    private SocketMessage<?> firstQuestionMessage() {
        return QuestionPushMessage.of(new QuestionPushMessage(
                QUESTION_ID, "QUESTION", "지원 동기를 말씀해 주세요.",
                null, false, "지원동기", "L1", 1, 9));
    }

    @Test
    void 연결되면_등록하고_연결_이벤트를_발행한다() {
        WebSocketSession socket = openSocket("s1");

        handler.afterConnectionEstablished(socket);

        assertThat(registry.find(SESSION_ID)).contains(socket);
        ArgumentCaptor<InterviewSocketConnectedEvent> captor =
                ArgumentCaptor.forClass(InterviewSocketConnectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(captor.getValue().socketId()).isEqualTo("s1");
    }

    @Test
    void 폴링push_와_연결복구가_같은_소켓에_겹쳐도_첫질문은_한_번만_간다() throws Exception {
        // interleaving: 폴링 fan-out 과 특정 소켓 복구가 같은 소켓을 친다.
        WebSocketSession socket = openSocket("s1");
        registry.register(SESSION_ID, socket);

        int byPoller = handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());
        boolean byCatchUp =
                handler.pushFirstQuestionTo(SESSION_ID, "s1", QUESTION_ID, firstQuestionMessage());

        assertThat(byPoller).isEqualTo(1);
        assertThat(byCatchUp).isFalse();
        verify(socket, times(1)).sendMessage(any());
    }

    @Test
    void 복구가_먼저_가고_폴링push_가_나중에_와도_첫질문은_한_번만_간다() throws Exception {
        WebSocketSession socket = openSocket("s1");
        registry.register(SESSION_ID, socket);

        boolean byCatchUp =
                handler.pushFirstQuestionTo(SESSION_ID, "s1", QUESTION_ID, firstQuestionMessage());
        int byPoller = handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());

        assertThat(byCatchUp).isTrue();
        assertThat(byPoller).isEqualTo(0);
        verify(socket, times(1)).sendMessage(any());
    }

    @Test
    void 서로_다른_소켓에는_각각_첫질문을_보낸다() throws Exception {
        WebSocketSession s1 = openSocket("s1");
        WebSocketSession s2 = openSocket("s2");
        registry.register(SESSION_ID, s1);
        registry.register(SESSION_ID, s2);

        int delivered = handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());

        assertThat(delivered).isEqualTo(2);
        verify(s1, times(1)).sendMessage(any());
        verify(s2, times(1)).sendMessage(any());
    }

    @Test
    void sendMessage_가_실패하면_전송완료로_고착되지_않고_다음_복구에서_다시_보낸다() throws Exception {
        // claim-after-send: 전송 실패 시 claim 이 남지 않아 이후 복구가 다시 보낼 수 있어야 한다.
        WebSocketSession socket = openSocket("s1");
        registry.register(SESSION_ID, socket);
        doThrow(new IOException("broken pipe"))
                .doNothing()
                .when(socket).sendMessage(any());

        int firstTry = handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());
        assertThat(firstTry).isEqualTo(0);

        boolean secondTry =
                handler.pushFirstQuestionTo(SESSION_ID, "s1", QUESTION_ID, firstQuestionMessage());
        assertThat(secondTry).isTrue();

        verify(socket, times(2)).sendMessage(any());
    }

    @Test
    void 사라진_소켓으로의_복구는_아무것도_하지_않는다() throws Exception {
        // 등록되지 않은(이미 끊긴) socketId 로 복구를 시도해도 안전해야 한다.
        boolean delivered =
                handler.pushFirstQuestionTo(SESSION_ID, "gone", QUESTION_ID, firstQuestionMessage());

        assertThat(delivered).isFalse();
    }
}
