package com.cuea.domain.growth.service;

import com.cuea.domain.report.service.FinishedReport;

import java.time.OffsetDateTime;

/**
 * 비교할 회차 하나.
 *
 * @param round     연습 기록의 회차 번호. 중단된 재면접은 세지 않습니다
 * @param createdAt 그 회차 면접 시작 시각
 */
public record ComparedRound(int round, OffsetDateTime createdAt, FinishedReport report) {
}
