package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.websocket.message.ReportProgressStage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 리포트 생성 상태. 소켓에 늦게 붙은 프론트가 현재 상태를 맞추는 데 씁니다.
 *
 * <p>점수와 리포트 본문은 담지 않습니다. 가벼운 조회용이고 본문은 상세 조회 API 몫입니다.
 *
 * @param sessionId   FAILED 후 재요청({@code POST /api/interviews/{sessionId}/reports})에 씁니다.
 *                    새로고침으로 들어온 프론트는 reportId 만 알고 있을 수 있습니다
 * @param stage       PROCESSING 일 때 마지막 진행 단계. 아직 없으면 null
 * @param progress    PROCESSING 일 때 0~1. 아직 없거나 AI 가 주지 않았으면 null
 * @param errorCode   FAILED 일 때만
 * @param message     FAILED 일 때만. WebSocket {@code error.message} 와 같은 문구입니다
 * @param retryable   FAILED 일 때만. WebSocket {@code error.retryable} 과 같은 규칙입니다
 * @param createdAt   최초 요청 시각. FAILED 후 재요청해도 바뀌지 않습니다
 * @param completedAt 끝난 시각(완료 또는 실패). 재요청하면 다시 null 입니다
 */
public record ReportStatusResponse(
        String reportId,

        String sessionId,

        ReportStatus status,

        @Schema(description = "PROCESSING 일 때만. 첫 진행 알림 전이면 null")
        ReportProgressStage stage,

        @Schema(description = "PROCESSING 일 때만. 0~1")
        Double progress,

        @Schema(description = "FAILED 일 때만")
        String errorCode,

        @Schema(description = "FAILED 일 때만. 사용자에게 보여줄 문구. WebSocket error.message 와 같습니다")
        String message,

        @Schema(description = "FAILED 일 때만. true 면 등록 API 로 다시 요청할 수 있습니다")
        Boolean retryable,

        @Schema(description = "최초 분석 요청 시각")
        OffsetDateTime createdAt,

        @Schema(description = "분석 종료 시각(완료 또는 실패). PROCESSING 이면 null")
        OffsetDateTime completedAt
) {
}
