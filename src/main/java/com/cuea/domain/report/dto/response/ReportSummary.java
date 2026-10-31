package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 목록 한 줄에 붙는 리포트 요약.
 *
 * @param scoreTotal 종합 점수 0~100. 분석 중이거나 실패했으면 null
 */
@Schema(description = "리포트 요약")
public record ReportSummary(
        String reportId,
        @Schema(description = "PROCESSING | COMPLETED | PARTIAL | FAILED")
        ReportStatus status,
        Integer scoreTotal
) {
}
