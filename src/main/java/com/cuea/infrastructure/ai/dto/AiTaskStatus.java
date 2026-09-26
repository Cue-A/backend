package com.cuea.infrastructure.ai.dto;

/**
 * {@code GET /ai/tasks/{taskId}} 응답의 공통 부분.
 *
 * <p>질문 생성과 리포트 생성은 같은 경로로 폴링하지만 {@code result} 모양이 다릅니다.
 * {@code AiPoller} 는 이 인터페이스만 보고 종료 여부를 판단하므로, 결과 타입이
 * 늘어나도 폴링 로직은 그대로 씁니다.
 *
 * <p>{@code processing} · {@code error} 응답에는 {@code result} 가 없습니다.
 * 결과를 꺼내기 전에 반드시 {@link #isDone()} 을 먼저 보세요.
 */
public interface AiTaskStatus {

    String STATUS_PROCESSING = "processing";
    String STATUS_DONE = "done";
    /** 최신 계약의 실패 상태. */
    String STATUS_ERROR = "error";
    /** 레거시/mock 호환용 실패 상태. */
    String STATUS_FAILED = "failed";

    String status();

    /** AI 내부 파이프라인 단계. 프론트에 그대로 내보내지 말고 enum 으로 옮기세요. */
    String stage();

    String errorCode();

    String message();

    default boolean isDone() {
        return STATUS_DONE.equals(status());
    }

    default boolean isFailed() {
        return STATUS_ERROR.equals(status()) || STATUS_FAILED.equals(status());
    }
}
