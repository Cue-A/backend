package com.cuea.infrastructure.ai.dto;

/** AI 서버가 4xx·5xx 로 답할 때의 본문. */
@AiJson
public record AiErrorResponse(
        String errorCode,
        String message
) {
}
