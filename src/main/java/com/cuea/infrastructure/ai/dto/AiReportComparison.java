package com.cuea.infrastructure.ai.dto;

import java.util.List;

/**
 * POST /ai/reports/compare 응답. 계약 15장.
 *
 * <p>배열({@code trend.overall} · {@code trend.axes.*} · {@code by_question[].scores})의 n번째 값은
 * {@code compared_rounds} 의 n번째 회차입니다. 값이 없는 칸은 null 입니다.
 *
 * <p>모르는 키는 무시합니다. {@code by_question[].text} 는 "1번 지원동기" 같은 자리표시라 읽지 않고,
 * 질문 원문은 Backend 가 붙입니다.
 *
 * @param comparedRounds 보낸 회차 번호 그대로, 오름차순
 * @param partialRounds  부분 리포트였던 회차
 * @param vsPrevious     직전 회차 대비. 회차가 하나면 null
 * @param byQuestion     비교한 모든 회차에 있는 재면접 주질문만. 회차가 하나면 빈 배열
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

    /**
     * 배열에서 바로 앞 회차와 비교합니다. 회차가 {@code [1, 3]} 이면 3 vs 1 입니다.
     *
     * @param overallDelta 두 회차 중 하나가 부분 리포트면 null
     * @param axisDelta    한쪽 축이 실패 · 미사용이면 그 축만 null
     */
    @AiJson
    public record VsPrevious(
            Integer fromRound,
            Integer toRound,
            Integer overallDelta,
            AxisDelta axisDelta,
            List<String> improved,
            List<String> declined,
            List<String> unchanged,
            String comment
    ) {
    }

    @AiJson
    public record AxisDelta(Integer content, Integer speech, Integer gaze) {
    }

    /**
     * @param overall     부분 리포트 회차는 null
     * @param stalledAxes 최근 3회차 동안 정체된 축
     * @param bestRound   종합 점수가 있는 회차 중 최고. 전부 null 이면 마지막 회차
     */
    @AiJson
    public record Trend(
            List<Integer> overall,
            AxisTrend axes,
            String comment,
            List<String> stalledAxes,
            Integer bestRound
    ) {
    }

    /** 축별 회차 점수. 실패 · 미사용인 회차는 null. */
    @AiJson
    public record AxisTrend(List<Integer> content, List<Integer> speech, List<Integer> gaze) {
    }

    /**
     * @param questionId 모든 회차에서 같은 ID 입니다. 재면접이 1회차 순서를 그대로 재생하기 때문입니다
     * @param category   AI 8종 카테고리 원문
     * @param scores     회차별 문항 종합 점수({@code questions[].score}). 모든 회차에 있는 문항만 나오므로 null 이 없습니다
     * @param deltaFromFirst 비교한 첫 회차 대비. 1회차가 빠졌으면 첫 재면접 대비입니다
     */
    @AiJson
    public record QuestionChange(
            String questionId,
            String category,
            List<Integer> scores,
            Integer deltaFromPrevious,
            Integer deltaFromFirst,
            String comment
    ) {
    }
}
