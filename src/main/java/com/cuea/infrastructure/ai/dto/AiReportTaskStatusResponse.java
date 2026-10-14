package com.cuea.infrastructure.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * GET /ai/tasks/{taskId} — 리포트 생성 작업.
 *
 * <p>{@code result} 는 {@link JsonNode} 원본으로 받습니다. 리포트 JSON 은 통째로
 * {@code report.report_data} 에 보관하고(회차 비교 때 AI 에 다시 보냄), 컬럼으로 꺼낼
 * 점수 몇 개만 {@link #summary()} 로 camelCase 로 옮깁니다. 계약서의 미확정 항목
 * ({@code metrics} 세부 필드 등)이 채워져도 이 DTO 는 바뀌지 않습니다.
 *
 * @param stage    transcribing | analyzing_speech | analyzing_gaze | analyzing_content | composing
 * @param progress 0~1. processing 일 때만 옵니다
 * @param result   done 일 때만 옵니다. {@code content} 축이 실패하면 done 이 아니라 error 로 옵니다
 */
@AiJson
public record AiReportTaskStatusResponse(
        String status,
        String stage,
        Double progress,
        JsonNode result,
        String errorCode,
        String message
) implements AiTaskStatus {

    /** 점수와 {@code report_status}. done 일 때만 의미가 있습니다. */
    public AiReportSummary summary() {
        return AiReportSummary.from(result);
    }
}
