package com.cuea.infrastructure.ai.dto;

import java.util.List;

/**
 * POST /ai/reports/compare 응답. 계약 15장. 필드 의미는 프론트로 옮긴 {@code ReportComparisonResponse} 에 적었습니다.
 *
 * <p>배열의 n번째 값은 {@code compared_rounds} 의 n번째 회차입니다. 모르는 키는 무시하고,
 * {@code by_question[].text} 는 "1번 지원동기" 같은 자리표시라 읽지 않습니다.
 *
 * @param comparedRounds 보낸 회차 번호 그대로, 오름차순
 * @param vsPrevious     배열의 바로 앞 회차 대비. 회차가 하나면 null
 */
@AiJson
public record AiReportComparison(
        Integer latestRound,
        List<Integer> comparedRounds,
        List<Integer> partialRounds,
        VsPrevious vsPrevious,
        Trend trend,
        List<QuestionChange> byQuestion
) {

    @AiJson
    public record VsPrevious(Integer fromRound, Integer toRound, Integer overallDelta, AxisDelta axisDelta,
                             List<String> improved, List<String> declined, List<String> unchanged, String comment) {
    }

    @AiJson
    public record AxisDelta(Integer content, Integer speech, Integer gaze) {
    }

    @AiJson
    public record Trend(List<Integer> overall, AxisTrend axes, String comment, List<String> stalledAxes,
                        Integer bestRound) {
    }

    @AiJson
    public record AxisTrend(List<Integer> content, List<Integer> speech, List<Integer> gaze) {
    }

    /** @param questionId 모든 회차에서 같은 ID 입니다. 재면접이 1회차 순서를 그대로 재생하기 때문입니다 */
    @AiJson
    public record QuestionChange(String questionId, String category, List<Integer> scores,
                                 Integer deltaFromPrevious, Integer deltaFromFirst, String comment) {
    }
}
