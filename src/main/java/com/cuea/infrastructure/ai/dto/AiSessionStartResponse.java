package com.cuea.infrastructure.ai.dto;

/** POST /ai/sessions 의 202 응답. session_id 는 AI 가 발급한 값을 그대로 씁니다. */
@AiJson
public record AiSessionStartResponse(
        String sessionId,
        String taskId,
        Integer questionTotal
) {
}
