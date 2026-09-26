package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.report.entity.ReportStatus;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * AI 리포트 결과에서 Backend 가 컬럼으로 꺼내는 값.
 *
 * <p>나머지는 해석하지 않고 원본({@code raw})을 그대로 보관합니다. 계약서의 미확정
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

    private static final String COMPLETE = "complete";
    private static final String PARTIAL = "partial";

    /**
     * @throws BusinessException 결과가 없거나 {@code report_status} 를 모르면
     *                           {@code UNEXPECTED_AI_RESPONSE}
     */
    public static ReportResult from(JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "AI 가 리포트 결과를 주지 않았습니다");
        }
        String reportStatus = result.path("report_status").asText(null);
        ReportStatus status;
        if (COMPLETE.equals(reportStatus)) {
            status = ReportStatus.COMPLETED;
        } else if (PARTIAL.equals(reportStatus)) {
            status = ReportStatus.PARTIAL;
        } else {
            throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE,
                    "알 수 없는 report_status 입니다: " + reportStatus);
        }

        JsonNode axes = result.path("axes");
        return new ReportResult(
                status,
                intOrNull(result.path("overall").path("score")),
                intOrNull(axes.path("content").path("score")),
                intOrNull(axes.path("speech").path("score")),
                intOrNull(axes.path("gaze").path("score")),
                result);
    }

    /** 실패·skipped 축은 {@code "score": null} 로 옵니다. 키가 없어도 null 로 봅니다. */
    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.intValue() : null;
    }
}
