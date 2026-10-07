package com.cuea.infrastructure.websocket;

import com.cuea.domain.interview.service.InterviewFirstQuestionCatchUp;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import com.cuea.infrastructure.websocket.message.SocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 첫 질문 복구(catch-up)와 소켓별 중복 전송 방지를 검증합니다.
 */
class SessionSocketHandlerCatchUpTest {

    private static final String SESSION_ID = "sess_1";
    private static final String QUESTION_ID = "q_1";

    private SocketSessionRegistry registry;
    private InterviewFirstQuestionCatchUp catchUp;
    private SessionSocketHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SocketSessionRegistry();
        catchUp = mock(InterviewFirstQuestionCatchUp.class);
        handler = new SessionSocketHandler(registry, new ObjectMapper(), catchUp);
    }

    /**
     * 열려 있는 소켓 mock. production 경로가 session attribute 에 claim 을 기록하므로
     * mutable·thread-safe attribute map 을 쥐여 준다.
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

    private InterviewFirstQuestionCatchUp.FirstQuestion firstQuestion() {
        return new InterviewFirstQuestionCatchUp.FirstQuestion(QUESTION_ID, firstQuestionMessage());
    }

    @Test
    void 연결_전에_드롭된_첫질문이_있으면_연결_직후_그_소켓에_복구_push_한다() throws Exception {
        WebSocketSession socket = openSocket("s1");
        when(catchUp.firstQuestion(SESSION_ID)).thenReturn(Optional.of(firstQuestion()));

        handler.afterConnectionEstablished(socket);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"type\":\"question\"");
        assertThat(payload).contains(QUESTION_ID);
        assertThat(registry.find(SESSION_ID)).contains(socket);
    }

    @Test
    void 복구할_첫질문이_없으면_아무것도_push_하지_않지만_등록은_유지된다() throws Exception {
        WebSocketSession socket = openSocket("s1");
        when(catchUp.firstQuestion(SESSION_ID)).thenReturn(Optional.empty());

        handler.afterConnectionEstablished(socket);

        verify(socket, never()).sendMessage(any());
        assertThat(registry.find(SESSION_ID)).contains(socket);
    }

    @Test
    void 복구_조회가_실패해도_연결은_유지되고_등록은_남는다() throws Exception {
        WebSocketSession socket = openSocket("s1");
        when(catchUp.firstQuestion(SESSION_ID)).thenThrow(new RuntimeException("DB down"));

        handler.afterConnectionEstablished(socket);

        verify(socket, never()).sendMessage(any());
        assertThat(registry.find(SESSION_ID)).contains(socket);
    }

    @Test
    void register_후_폴링push_그리고_catchup_이_겹쳐도_같은_소켓에_첫질문은_한_번만_간다() throws Exception {
        // interleaving: 연결(catch-up 전송) 직후 폴링이 같은 소켓에 다시 민다.
        WebSocketSession socket = openSocket("s1");
        when(catchUp.firstQuestion(SESSION_ID)).thenReturn(Optional.of(firstQuestion()));

        handler.afterConnectionEstablished(socket);
        int deliveredByPoller =
                handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());

        assertThat(deliveredByPoller).isEqualTo(0);
        verify(socket, times(1)).sendMessage(any());
    }

    @Test
    void 폴링push_가_먼저_가고_그_다음_catchup_이_와도_같은_소켓에_첫질문은_한_번만_간다() throws Exception {
        // 반대 순서 interleaving: 폴링 전송 뒤 연결 복구가 같은 소켓에 다시 시도.
        WebSocketSession socket = openSocket("s1");
        registry.register(SESSION_ID, socket);

        int deliveredByPoller =
                handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());
        boolean deliveredByCatchUp =
                handler.pushFirstQuestionTo(socket, QUESTION_ID, firstQuestionMessage());

        assertThat(deliveredByPoller).isEqualTo(1);
        assertThat(deliveredByCatchUp).isFalse();
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
    void 연결_먼저_정상경로_복구없음이면_폴링push_만_한_번_전달한다() throws Exception {
        WebSocketSession socket = openSocket("s1");
        when(catchUp.firstQuestion(SESSION_ID)).thenReturn(Optional.empty());
        handler.afterConnectionEstablished(socket);

        int delivered = handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());

        assertThat(delivered).isEqualTo(1);
        verify(socket, atMostOnce()).sendMessage(any());
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

        boolean secondTry = handler.pushFirstQuestionTo(socket, QUESTION_ID, firstQuestionMessage());
        assertThat(secondTry).isTrue();

        verify(socket, times(2)).sendMessage(any());
    }

    @Test
    void 전송_성공_후에는_같은_소켓_재시도가_전송하지_않는다() throws Exception {
        WebSocketSession socket = openSocket("s1");
        registry.register(SESSION_ID, socket);

        boolean first = handler.pushFirstQuestionTo(socket, QUESTION_ID, firstQuestionMessage());
        boolean second = handler.pushFirstQuestionTo(socket, QUESTION_ID, firstQuestionMessage());

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        verify(socket, times(1)).sendMessage(any());
    }
}
