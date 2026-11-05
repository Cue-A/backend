package com.cuea.domain.interview.service;

import java.time.OffsetDateTime;

/**
 * 같은 루트 묶음 안의 세션 하나와 그 회차.
 *
 * @param round     연습 기록 목록의 회차와 같은 번호. 중단된 재연습은 세지 않습니다
 * @param createdAt 면접 시작 시각
 */
public record SessionRound(String sessionId, int round, OffsetDateTime createdAt) {
}
