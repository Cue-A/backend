package com.cuea.infrastructure.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 리포트 결과에서 Backend 가 컬럼으로 꺼내는 값만 camelCase 로 옮긴 것.
 *
 * <p>AI 리포트 JSON 의 snake_case 키({@code report_status}, {@code axes.gaze.score} 등)를
 * 읽는 곳은 여기뿐입니다. 도메인은 이 레코드와 원본 {@link JsonNode}(통째로 보관만 함)를
 * 받습니다. docs/01-conventions.md 의 "도메인 안으로 snake_case 를 들이지 않습니다" 참고.
 *
 * @param reportStatus {@link #COMPLETE} | {@link #PARTIAL}. 결과가 비었으면 null
 * @param gazeScore    시선 축이 실패했거나 {@code skipped}(카메라 미사용)면 null
 */
public record AiReportSummary(
        String reportStatus,
        Integer overallScore,
        Integer contentScore,
        Integer speechScore,
        Integer gazeScore
) {

    public static final String COMPLETE = "complete";
    public static final String PARTIAL = "partial";

    static AiReportSummary from(JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return new AiReportSummary(null, null, null, null, null);
        }
        JsonNode axes = result.path("axes");
        return new AiReportSummary(
                result.path("report_status").asText(null),
                intOrNull(result.path("overall").path("score")),
                intOrNull(axes.path("content").path("score")),
                intOrNull(axes.path("speech").path("score")),
                intOrNull(axes.path("gaze").path("score")));
    }

    /** 실패·skipped 축은 {@code "score": null} 로 옵니다. 키가 없어도 null 로 봅니다. */
    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.intValue() : null;
    }
}
