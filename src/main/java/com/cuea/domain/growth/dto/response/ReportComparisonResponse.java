package com.cuea.domain.growth.dto.response;

import com.cuea.domain.growth.service.ComparedRound;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.ai.dto.AiReportComparison;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 회차 비교 · 성장 추이.
 *
 * <p>AI 응답(계약 15장)을 camelCase 로 옮기고, AI 가 모르는 회차별 리포트 정보({@code rounds})와 질문 원문
 * ({@code byQuestion[].questionText})을 붙입니다. AI 의 {@code by_question[].text} 는 자리표시라 내보내지 않습니다.
 *
 * <p>배열({@code trend.overall} · {@code trend.axes.*} · {@code byQuestion[].scores} · {@code rounds})의 n번째 값은
 * {@code comparedRounds} 의 n번째 회차입니다. <b>배열 인덱스로 회차 번호를 만들면 안 됩니다.</b> {@code [1, 3]} 처럼
 * 빌 수 있습니다.
 */
@Schema(description = "회차 비교 · 성장 추이")
public record ReportComparisonResponse(

        @Schema(description = "기준 리포트. 이 리포트의 회차가 마지막 회차입니다")
        String reportId,

        @Schema(description = "마지막(= 이 리포트) 회차")
        int latestRound,

        @Schema(description = "비교한 회차 번호, 오름차순. 배열 값의 n번째가 이 n번째 회차입니다. [1, 3] 처럼 빌 수 있습니다")
        List<Integer> comparedRounds,

        @Schema(description = "부분 리포트였던 회차. 이 회차의 trend.overall 은 null 입니다")
        List<Integer> partialRounds,

        @Schema(description = "비교한 회차의 리포트 정보. comparedRounds 와 같은 순서")
        List<Round> rounds,

        @Schema(description = "직전 회차 대비. 비교할 이전 회차가 없으면 null")
        VsPrevious vsPrevious,

        Trend trend,

        @Schema(description = "문항별 변화. 비교한 모든 회차에서 다시 받은 주질문만. 꼬리질문 · 되묻기 · 대체 질문은 없고, 회차가 하나면 빈 배열")
        List<QuestionChange> byQuestion
) {

    /**
     * @param reportId  그 회차 리포트. 그래프 점을 눌러 이동할 때 씁니다
     * @param status    COMPLETED | PARTIAL
     * @param createdAt 그 회차 면접 시작 시각. 연습 기록의 createdAt 과 같습니다
     */
    public record Round(int round, String reportId, ReportStatus status, OffsetDateTime createdAt) {
    }

    /**
     * 「N회차 vs M회차」는 {@code toRound} vs {@code fromRound} 입니다. {@code latestRound - 1} 이 아닙니다.
     *
     * @param overallDelta 종합 점수 변화. 두 회차 중 하나가 부분 리포트면 null
     * @param axisDelta    축별 변화. 한쪽 축이 실패 · 미사용이면 그 축만 null
     * @param improved     좋아진 문항 ID. byQuestion[].questionId 와 같은 기준
     */
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

    public record AxisDelta(Integer content, Integer speech, Integer gaze) {
    }

    /**
     * @param overall     회차별 종합 점수. 부분 리포트 회차는 null 이며 0 과 다릅니다
     * @param stalledAxes 최근 3회차 동안 정체된 축
     * @param bestRound   가장 잘한 회차
     */
    public record Trend(
            List<Integer> overall,
            AxisTrend axes,
            String comment,
            List<String> stalledAxes,
            Integer bestRound
    ) {
    }

    /** 축별 회차 점수. 실패 · 미사용인 회차는 null. */
    public record AxisTrend(List<Integer> content, List<Integer> speech, List<Integer> gaze) {
    }

    /**
     * @param questionText 질문 원문. Backend 가 붙입니다. 못 찾으면 null
     * @param category     AI 8종 카테고리 원문
     * @param scores       회차별 문항 점수. 모든 회차에 있는 문항만 나오므로 null 이 없습니다
     * @param deltaFromFirst 비교한 첫 회차 대비. 1회차 리포트가 없으면 첫 재면접 대비입니다
     */
    public record QuestionChange(
            String questionId,
            String questionText,
            String category,
            List<Integer> scores,
            Integer deltaFromPrevious,
            Integer deltaFromFirst,
            String comment
    ) {
    }

    /**
     * @param rounds        비교한 회차. AI 의 {@code compared_rounds} 와 같은지 이미 확인했습니다
     * @param questionTexts 질문 ID → 원문
     */
    public static ReportComparisonResponse of(String reportId, List<ComparedRound> rounds,
                                              AiReportComparison result, Map<String, String> questionTexts) {
        return new ReportComparisonResponse(
                reportId,
                rounds.getLast().round(),
                rounds.stream().map(ComparedRound::round).toList(),
                listOrEmpty(result.partialRounds()),
                rounds.stream().map(r -> new Round(r.round(), r.report().reportId(), r.report().status(), r.createdAt()))
                        .toList(),
                vsPrevious(result.vsPrevious()),
                trend(result.trend()),
                listOrEmpty(result.byQuestion()).stream()
                        .map(q -> new QuestionChange(q.questionId(), questionTexts.get(q.questionId()), q.category(),
                                q.scores(), q.deltaFromPrevious(), q.deltaFromFirst(), q.comment()))
                        .toList());
    }

    private static VsPrevious vsPrevious(AiReportComparison.VsPrevious v) {
        if (v == null) {
            return null;
        }
        AiReportComparison.AxisDelta d = v.axisDelta();
        return new VsPrevious(v.fromRound(), v.toRound(), v.overallDelta(),
                d == null ? new AxisDelta(null, null, null) : new AxisDelta(d.content(), d.speech(), d.gaze()),
                listOrEmpty(v.improved()), listOrEmpty(v.declined()), listOrEmpty(v.unchanged()), v.comment());
    }

    private static Trend trend(AiReportComparison.Trend t) {
        AiReportComparison.AxisTrend a = t.axes();
        return new Trend(t.overall(),
                a == null ? new AxisTrend(null, null, null) : new AxisTrend(a.content(), a.speech(), a.gaze()),
                t.comment(), listOrEmpty(t.stalledAxes()), t.bestRound());
    }

    private static <T> List<T> listOrEmpty(List<T> source) {
        return source == null ? List.of() : source;
    }
}
