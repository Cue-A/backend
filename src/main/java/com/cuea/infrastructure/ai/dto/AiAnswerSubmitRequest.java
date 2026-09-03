package com.cuea.infrastructure.ai.dto;

/**
 * POST /ai/sessions/{sessionId}/answers
 *
 * <p>답변 오디오는 프론트가 S3 에 직접 올리고, 우리는 URL 만 넘깁니다.
 */
@AiJson
public record AiAnswerSubmitRequest(
        String questionId,
        String answerAudioUrl
) {
}
