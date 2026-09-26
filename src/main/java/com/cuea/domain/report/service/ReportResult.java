package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.ai.dto.AiReportSummary;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * AI 리포트 결과에서 Backend 가 컬럼으로 꺼내는 값.
 *
 * <p>AI JSON 의 키를 직접 읽지 않습니다. 점수는 {@link AiReportSummary} 가 옮겨 주고,
 * 나머지는 해석하지 않고 원본({@code raw})을 그대로 보관합니다. 계약서의 미확정
 * 항목(metrics 세부 필드, 가중치 등)이 채워져도 여기는 바뀌지 않습니다.
 *
 * @param scoreGaze 시선 축이 실패했거나 {@code skipped}(카메라 미사용)면 null
 */
public record ReportResult(
        ReportStatus status,
        Integer scoreTotal,
        Integer scoreContent,
        Integer scoreSpeech,
        Integer scoreGaze,
        JsonNode raw
) {

    /**
     * @throws BusinessException 결과가 없거나 {@code report_status} 를 모르면
     *                           {@code UNEXPECTED_AI_RESPONSE}
     */
    public static ReportResult from(AiReportTaskStatusResponse done) {
        if (done.result() == null || done.result().isNull()) {
            throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "AI 가 리포트 결과를 주지 않았습니다");
        }
        AiReportSummary summary = done.summary();
        return new ReportResult(
                toStatus(summary.reportStatus()),
                summary.overallScore(),
                summary.contentScore(),
                summary.speechScore(),
                summary.gazeScore(),
                done.result());
    }

    private static ReportStatus toStatus(String reportStatus) {
        if (AiReportSummary.COMPLETE.equals(reportStatus)) {
            return ReportStatus.COMPLETED;
        }
        if (AiReportSummary.PARTIAL.equals(reportStatus)) {
            return ReportStatus.PARTIAL;
        }
        throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE,
                "알 수 없는 report_status 입니다: " + reportStatus);
    }
}
