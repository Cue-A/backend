package com.cuea.domain.interview.service;

/**
 * 면접 WebSocket 연결 하나가 등록됐다는 알림. {@link InterviewSocketConnectedListener}
 * 가 받아 첫 질문 복구를 판단합니다.
 *
 * <p>인프라(WebSocket 핸들러)가 도메인 로직을 직접 끌어오지 않도록, 핸들러는 이 이벤트만
 * 발행하고 복구 판단은 도메인 리스너가 맡습니다. 특정 연결에만 복구를 보내야 하므로
 * {@code socketId} 를 함께 싣습니다. Spring 의 {@code WebSocketSession} 타입은 도메인에
 * 노출하지 않습니다.
 */
public record InterviewSocketConnectedEvent(String sessionId, String socketId) {
}
