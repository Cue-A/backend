package com.cuea.infrastructure.ai.dto;

/**
 * GET /ai/tasks/{taskId}
 *
 * @param status processing | done | error — 최신 AI 계약의 실패 상태는 {@code error} 입니다.
 *               레거시/mock 호환을 위해 {@code failed} 도 실패로 인식합니다.
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
    /** 최신 계약의 실패 상태. */
    public static final String STATUS_ERROR = "error";
    /** 레거시/mock 호환용 실패 상태. */
    public static final String STATUS_FAILED = "failed";

    public boolean isDone() {
        return STATUS_DONE.equals(status);
    }

    public boolean isFailed() {
        return STATUS_ERROR.equals(status) || STATUS_FAILED.equals(status);
    }
}
