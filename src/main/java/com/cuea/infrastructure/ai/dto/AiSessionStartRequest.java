package com.cuea.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * POST /ai/sessions
 *
 * <p>토픽 수는 보내지 않습니다. question_count 에서 자동 결정됩니다.
 *
 * @param resumeFileUrl    Presigned GET URL (만료 15분)
 * @param persona          friendly | pressure
 * @param companyId        null 허용 (회사 미선택 연습 모드)
 * @param retryOfSessionId 재연습이면 최초 세션 ID
 * @param replayLog        재연습이면 필수
 */
@AiJson
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiSessionStartRequest(
        String resumeFileUrl,
        String jobRole,
        String persona,
        String companyId,
        Integer questionCount,
        String retryOfSessionId,
        List<AiReplayLogItem> replayLog
) {
}
