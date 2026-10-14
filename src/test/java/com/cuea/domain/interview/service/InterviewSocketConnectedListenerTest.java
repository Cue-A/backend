package com.cuea.domain.interview.service;

import com.cuea.infrastructure.websocket.SessionSocketHandler;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import com.cuea.infrastructure.websocket.message.SocketMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 연결 이벤트를 받아 첫 질문을 복구하는 리스너를 검증합니다. 복구 대상이 있으면 그
 * 소켓에만 전송하고, 복구 실패가 연결(이벤트 처리)을 깨뜨리지 않아야 합니다.
 */
class InterviewSocketConnectedListenerTest {

    private static final String SESSION_ID = "sess_1";
    private static final String SOCKET_ID = "s1";
    private static final String QUESTION_ID = "q_1";

    private InterviewFirstQuestionCatchUp catchUp;
    private SessionSocketHandler socketHandler;
    private InterviewSocketConnectedListener listener;

    @BeforeEach
    void setUp() {
        catchUp = mock(InterviewFirstQuestionCatchUp.class);
        socketHandler = mock(SessionSocketHandler.class);
        listener = new InterviewSocketConnectedListener(catchUp, socketHandler);
    }

    private SocketMessage<?> firstQuestionMessage() {
        return QuestionPushMessage.of(new QuestionPushMessage(
                QUESTION_ID, "QUESTION", "지원 동기를 말씀해 주세요.",
                null, false, "지원동기", "L1", 1, 9));
    }

    @Test
    void 복구할_첫질문이_있으면_방금_연결된_소켓에만_전송한다() {
        SocketMessage<?> message = firstQuestionMessage();
        when(catchUp.firstQuestion(SESSION_ID)).thenReturn(
                Optional.of(new InterviewFirstQuestionCatchUp.FirstQuestion(QUESTION_ID, message)));

        listener.onConnected(new InterviewSocketConnectedEvent(SESSION_ID, SOCKET_ID));

        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).pushFirstQuestionTo(eq(SESSION_ID), eq(SOCKET_ID), eq(QUESTION_ID), captor.capture());
        assertThat(captor.getValue()).isSameAs(message);
    }

    @Test
    void 복구할_첫질문이_없으면_아무것도_전송하지_않는다() {
        when(catchUp.firstQuestion(SESSION_ID)).thenReturn(Optional.empty());

        listener.onConnected(new InterviewSocketConnectedEvent(SESSION_ID, SOCKET_ID));

        verify(socketHandler, never()).pushFirstQuestionTo(any(), any(), any(), any());
    }

    @Test
    void 복구_조회가_실패해도_예외가_새지_않는다() {
        when(catchUp.firstQuestion(SESSION_ID)).thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> listener.onConnected(new InterviewSocketConnectedEvent(SESSION_ID, SOCKET_ID)))
                .doesNotThrowAnyException();
        verify(socketHandler, never()).pushFirstQuestionTo(any(), any(), any(), any());
    }
}
