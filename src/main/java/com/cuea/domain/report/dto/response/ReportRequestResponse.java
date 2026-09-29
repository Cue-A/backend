package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 분석 작업 등록 응답. 결과는 WebSocket {@code /ws/reports/{reportId}} 로 옵니다.
 *
 * @param reportId 리포트의 public_id(UUID). 실패 후 재요청해도 같은 값입니다
 */
public record ReportRequestResponse(
        @Schema(description = "리포트 ID. WebSocket 경로와 이후 조회에 씁니다")
        String reportId,

        String sessionId,

        @Schema(description = "등록 직후에는 항상 PROCESSING")
        ReportStatus status,

        OffsetDateTime createdAt
) {

    public static ReportRequestResponse of(Report report, String sessionId) {
        return new ReportRequestResponse(
                report.getPublicId().toString(),
                sessionId,
                report.getStatus(),
                report.getCreatedAt());
    }
}
