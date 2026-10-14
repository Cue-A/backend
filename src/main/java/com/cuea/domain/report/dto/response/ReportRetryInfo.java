package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.ReportRetryStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * PARTIAL 리포트의 실패 축 재시도 상태. 재시도 · 상세 조회 · 상태 조회 응답에 같은 모양으로 들어갑니다.
 *
 * <p>재시도한 적이 없거나 재시도가 성공해 결과가 교체됐으면 응답의 {@code retry} 는 null 입니다.
 *
 * @param axes      다시 분석하는 축. 리포트의 {@code overall.axesFailed} 와 같습니다
 * @param errorCode FAILED 일 때만
 * @param message   FAILED 일 때만. 리포트 실패 문구와 같은 규칙입니다
 * @param retryable FAILED 일 때만. true 면 재시도 API 로 다시 요청할 수 있습니다
 */
@Schema(description = "실패 축 재시도 상태. 재시도한 적이 없거나 성공했으면 null")
public record ReportRetryInfo(
        @Schema(description = "PROCESSING(재시도 중) | FAILED(마지막 재시도 실패)")
        ReportRetryStatus status,

        @Schema(description = "다시 분석하는 축. PROCESSING 이면 이 축의 칸에만 로딩을 보여줍니다")
        List<String> axes,

        String errorCode,

        String message,

        Boolean retryable
) {
}
