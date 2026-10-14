package com.cuea.infrastructure.ai.dto;

/** 비동기 작업 등록 응답. 이 task_id 로 폴링합니다. */
@AiJson
public record AiTaskAcceptedResponse(String taskId) {
}
