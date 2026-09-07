package com.cuea.infrastructure.ai.dto;

/**
 * GET /ai/tasks/{taskId}
 *
 * @param status processing | done | failed
 * @param stage  stt | generating | tts — 프론트에 그대로 내보내지 마세요.
 *               ProgressStage 로 매핑합니다. docs/10-ai-client.md 참고.
 */
@AiJson
public record AiTaskStatusResponse(
        String status,
        String stage,
        AiQuestionResult result,
        String errorCode,
        String message
) {

    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    public boolean isDone() {
        return STATUS_DONE.equals(status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equals(status);
    }
}
