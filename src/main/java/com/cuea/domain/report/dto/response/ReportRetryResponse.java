package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 실패 축 재시도 접수 응답. 결과는 WebSocket {@code /ws/reports/{reportId}} 로 옵니다.
 *
 * <p>재시도를 시작했다는 확인일 뿐이라 점수는 담지 않습니다. 바뀐 점수는 WS {@code report} 를
 * 받은 뒤 상세 조회로 가져옵니다.
 */
public record ReportRetryResponse(
        String reportId,

        @Schema(description = "리포트 상태. 재시도 중에도 PARTIAL 입니다")
        ReportStatus status,

        @Schema(description = "재시도 상태. 접수 직후에는 항상 PROCESSING")
        ReportRetryInfo retry
) {
}
