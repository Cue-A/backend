package com.cuea.domain.interview.service;

import com.cuea.infrastructure.websocket.SessionSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * WebSocket 연결 직후 첫 질문 복구를 수행합니다.
 *
 * <p>인프라 핸들러는 연결 사실만 이벤트로 알리고, "무엇을 복구할지" 는 도메인이 정합니다
 * ({@link InterviewFirstQuestionCatchUp}). 복구할 첫 질문이 있으면 방금 연결된 그 소켓에만
 * 전송합니다.
 *
 * <p>이벤트는 동기로 처리합니다({@code @Async} 아님). 연결 콜백 스레드에서 register
 * 직후 바로 복구하므로, 복구와 그 사이의 폴링 push 가 겹쳐도 순서가 보장됩니다. 복구
 * 실패가 WebSocket 연결 자체를 끊지 않도록 예외를 흡수합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewSocketConnectedListener {

    private final InterviewFirstQuestionCatchUp catchUp;
    private final SessionSocketHandler socketHandler;

    @EventListener
    public void onConnected(InterviewSocketConnectedEvent event) {
        try {
            catchUp.firstQuestion(event.sessionId())
                    .ifPresent(fq -> socketHandler.pushFirstQuestionTo(
                            event.sessionId(), event.socketId(), fq.questionId(), fq.message()));
        } catch (RuntimeException e) {
            log.warn("연결 시점 첫 질문 복구 실패 sessionId={} socketId={}",
                    event.sessionId(), event.socketId(), e);
        }
    }
}
