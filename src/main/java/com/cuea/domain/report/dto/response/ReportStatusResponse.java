package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.websocket.message.ReportProgressStage;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 리포트 생성 상태. 소켓에 늦게 붙은 프론트가 현재 상태를 맞추는 데 씁니다.
 *
 * @param stage     PROCESSING 일 때 마지막 진행 단계. 아직 없으면 null
 * @param progress  PROCESSING 일 때 0~1. 아직 없거나 AI 가 주지 않았으면 null
 * @param errorCode FAILED 일 때만
 * @param retryable FAILED 일 때만. WebSocket {@code error.retryable} 과 같은 규칙입니다
 */
public record ReportStatusResponse(
        String reportId,

        ReportStatus status,

        @Schema(description = "PROCESSING 일 때만. 첫 진행 알림 전이면 null")
        ReportProgressStage stage,

        @Schema(description = "PROCESSING 일 때만. 0~1")
        Double progress,

        @Schema(description = "FAILED 일 때만")
        String errorCode,

        @Schema(description = "FAILED 일 때만. true 면 등록 API 로 다시 요청할 수 있습니다")
        Boolean retryable
) {
}
