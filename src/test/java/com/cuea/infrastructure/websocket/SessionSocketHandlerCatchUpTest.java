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
 * 면접 WebSocket 연결 시 첫 질문 복구(catch-up)와 소켓별 중복 방지를 검증합니다. (Issue #35)
 *
 * <p>핵심 두 가지:
 * <ul>
 *   <li>연결 전에 드롭된 첫 질문을 연결 직후 그 소켓에 복구한다(lost 방지).</li>
 *   <li>폴링 push 와 복구가 같은 소켓에 겹쳐도 소켓당 한 번만 보낸다(duplicate 방지).</li>
 * </ul>
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
     * 실제 컨테이너 세션처럼 attribute 맵을 들고, 열려 있는 소켓 mock. 첫 질문 클레임이
     * attribute 에 기록되므로 ConcurrentHashMap 을 붙여 둔다(AbstractWebSocketSession 과 동일).
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
        // Issue #35 리뷰에서 지적한 duplicate race 를 결정적으로 재현한다.
        // interleaving:
        //   1) afterConnectionEstablished: register → (catch-up 조회가 '방금 폴링이
        //      보냈다'는 사실을 모른 채) 복구를 시도
        //   2) 그 사이 폴링이 pushFirstQuestion 으로 같은 소켓에 이미 보냄
        // 두 경로 모두 pushFirstQuestion/pushFirstQuestionTo 를 거치고, 소켓별 questionId
        // 클레임이 공유되므로 실제 sendMessage 는 소켓당 정확히 1회여야 한다.
        WebSocketSession socket = openSocket("s1");
        // catch-up 은 폴링과 동일한 첫 질문을 돌려주도록 스텁(실서비스에서도 같은 DB 질문).
        when(catchUp.firstQuestion(SESSION_ID)).thenReturn(Optional.of(firstQuestion()));

        // 1) 연결: register 안에서 catch-up 이 1회 전송(클레임 기록).
        handler.afterConnectionEstablished(socket);
        // 2) 폴링이 같은 소켓에 같은 첫 질문을 fan-out 으로 다시 민다.
        int deliveredByPoller =
                handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());

        // 폴링은 이미 클레임된 소켓이라 전송 0 건으로 중복을 막는다.
        assertThat(deliveredByPoller).isEqualTo(0);
        // 실제 전송은 소켓당 정확히 1회.
        verify(socket, times(1)).sendMessage(any());
    }

    @Test
    void 폴링push_가_먼저_가고_그_다음_catchup_이_와도_같은_소켓에_첫질문은_한_번만_간다() throws Exception {
        // 반대 순서 interleaving: 폴링이 먼저 전송(클레임) → 이후 연결 복구가 같은 소켓에
        // 다시 시도. 복구는 이미 클레임돼 전송하지 않는다.
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
        // 중복 방지는 '같은 소켓' 기준이다. 탭 두 개(소켓 둘)에는 각각 한 번씩 가야 한다.
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
        // WS 가 먼저 붙은 정상 경로. 복구 대상 없음(catch-up empty) → 연결 시 전송 0.
        // 이후 폴링이 pushFirstQuestion 으로 1회 전송.
        WebSocketSession socket = openSocket("s1");
        when(catchUp.firstQuestion(SESSION_ID)).thenReturn(Optional.empty());
        handler.afterConnectionEstablished(socket);

        int delivered = handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());

        assertThat(delivered).isEqualTo(1);
        verify(socket, atMostOnce()).sendMessage(any());
    }

    @Test
    void sendMessage_가_실패하면_전송완료로_고착되지_않고_다음_복구에서_다시_보낸다() throws Exception {
        // claim-after-send 실패 semantics 검증: 첫 전송이 IOException 으로 실패하면
        // 클레임을 남기지 않아야 하고, 이후 catch-up 이 같은 소켓에 다시 보낼 수 있어야 한다.
        WebSocketSession socket = openSocket("s1");
        registry.register(SESSION_ID, socket);
        // 1차 전송은 실패, 2차 전송은 성공하도록 순서대로 스텁.
        doThrow(new IOException("broken pipe"))
                .doNothing()
                .when(socket).sendMessage(any());

        // 1) 폴링이 등록된 소켓에 전송 시도 → IOException 으로 실패 → delivered 0, 클레임 미기록.
        int firstTry = handler.pushFirstQuestion(SESSION_ID, QUESTION_ID, firstQuestionMessage());
        assertThat(firstTry).isEqualTo(0);

        // 2) 이후 catch-up(또는 재전송)이 같은 소켓에 다시 시도 → 이번엔 성공.
        boolean secondTry = handler.pushFirstQuestionTo(socket, QUESTION_ID, firstQuestionMessage());
        assertThat(secondTry).isTrue();

        // 실패한 전송이 "전달 완료" 로 고착되지 않았으므로 총 2회 시도됐다.
        verify(socket, times(2)).sendMessage(any());
    }

    @Test
    void 전송_성공_후에는_같은_소켓_재시도가_전송하지_않는다() throws Exception {
        // 성공 후에는 클레임이 남아 같은 소켓 재시도는 전송하지 않는다(중복 방지).
        WebSocketSession socket = openSocket("s1");
        registry.register(SESSION_ID, socket);

        boolean first = handler.pushFirstQuestionTo(socket, QUESTION_ID, firstQuestionMessage());
        boolean second = handler.pushFirstQuestionTo(socket, QUESTION_ID, firstQuestionMessage());

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        verify(socket, times(1)).sendMessage(any());
    }
}
