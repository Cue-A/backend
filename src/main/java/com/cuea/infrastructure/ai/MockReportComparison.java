package com.cuea.infrastructure.ai;

import com.cuea.infrastructure.ai.dto.AiReportCompareRequest;
import com.cuea.infrastructure.ai.dto.AiReportComparison;
import com.cuea.infrastructure.ai.dto.AiReportComparison.AxisDelta;
import com.cuea.infrastructure.ai.dto.AiReportComparison.AxisTrend;
import com.cuea.infrastructure.ai.dto.AiReportComparison.QuestionChange;
import com.cuea.infrastructure.ai.dto.AiReportComparison.Trend;
import com.cuea.infrastructure.ai.dto.AiReportComparison.VsPrevious;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@link MockAiClient} 의 회차 비교. 보낸 리포트 원본의 점수만으로 실제 AI 와 같은 모양을 만들고,
 * 코멘트는 고정 문구입니다. AI 답변으로 확인한 규칙을 따릅니다.
 *
 * <ul>
 *   <li>부분 리포트 회차는 종합 점수가 null 이고, 어느 한쪽이 null 이면 변화량도 null</li>
 *   <li>실패 · 미사용 축(status 가 ok 가 아님)은 null</li>
 *   <li>문항은 비교한 모든 회차에 있는 것만. 1회차는 모든 문항, 재면접은 {@code is_replay} 문항이 대상</li>
 *   <li>회차가 하나면 {@code vs_previous} 는 null, {@code by_question} 은 빈 배열</li>
 * </ul>
 */
final class MockReportComparison {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private MockReportComparison() {
    }

    static AiReportComparison compare(AiReportCompareRequest request) {
        List<Round> rounds = request.reports().stream()
                .sorted(Comparator.comparingInt(AiReportCompareRequest.Round::round))
                .map(r -> new Round(r.round(), JSON.convertValue(r.report(), AiReportResult.class)))
                .toList();
        List<Integer> numbers = rounds.stream().map(Round::number).toList();
        List<Integer> overall = rounds.stream().map(Round::overall).toList();
        List<QuestionChange> byQuestion = byQuestion(rounds);

        return new AiReportComparison(
                numbers.getLast(),
                numbers,
                rounds.stream().filter(Round::partial).map(Round::number).toList(),
                vsPrevious(rounds, byQuestion),
                new Trend(overall,
                        new AxisTrend(axisTrend(rounds, AiReportResult.Axes::content),
                                axisTrend(rounds, AiReportResult.Axes::speech),
                                axisTrend(rounds, AiReportResult.Axes::gaze)),
                        "목 응답입니다. 회차별 점수를 그대로 이었습니다.",
                        List.of(),
                        bestRound(numbers, overall)),
                byQuestion);
    }

    private static VsPrevious vsPrevious(List<Round> rounds, List<QuestionChange> byQuestion) {
        if (rounds.size() < 2) {
            return null;
        }
        Round from = rounds.get(rounds.size() - 2);
        Round to = rounds.getLast();
        return new VsPrevious(
                from.number(),
                to.number(),
                minus(to.overall(), from.overall()),
                new AxisDelta(
                        minus(to.axis(AiReportResult.Axes::content), from.axis(AiReportResult.Axes::content)),
                        minus(to.axis(AiReportResult.Axes::speech), from.axis(AiReportResult.Axes::speech)),
                        minus(to.axis(AiReportResult.Axes::gaze), from.axis(AiReportResult.Axes::gaze))),
                questionIds(byQuestion, delta -> delta > 0),
                questionIds(byQuestion, delta -> delta < 0),
                questionIds(byQuestion, delta -> delta == 0),
                "목 응답입니다. %d회차와 비교했습니다.".formatted(from.number()));
    }

    /** 첫 회차의 문항 순서대로, 모든 회차에 있는 문항만. */
    private static List<QuestionChange> byQuestion(List<Round> rounds) {
        if (rounds.size() < 2) {
            return List.of();
        }
        List<Map<String, AiReportResult.Question>> comparable = rounds.stream().map(Round::comparableQuestions).toList();
        return comparable.getFirst().values().stream()
                .filter(q -> comparable.stream().allMatch(byId -> byId.containsKey(q.questionId())))
                .map(q -> {
                    List<Integer> scores = comparable.stream().map(byId -> byId.get(q.questionId()).score()).toList();
                    Integer last = scores.getLast();
                    return new QuestionChange(q.questionId(), q.category(), scores,
                            minus(last, scores.get(scores.size() - 2)), minus(last, scores.getFirst()),
                            "목 응답입니다. 문항 점수 변화를 그대로 보여줍니다.");
                })
                .toList();
    }

    private static List<String> questionIds(List<QuestionChange> byQuestion, Function<Integer, Boolean> matches) {
        return byQuestion.stream()
                .filter(q -> q.deltaFromPrevious() != null && matches.apply(q.deltaFromPrevious()))
                .map(QuestionChange::questionId)
                .toList();
    }

    private static List<Integer> axisTrend(List<Round> rounds, Function<AiReportResult.Axes, AiReportResult.Axis> axis) {
        return rounds.stream().map(r -> r.axis(axis)).toList();
    }

    /** 종합 점수가 있는 회차 중 최고. 같으면 나중 회차. 전부 없으면 마지막 회차입니다. */
    private static int bestRound(List<Integer> numbers, List<Integer> overall) {
        int best = numbers.getLast();
        Integer bestScore = null;
        for (int i = 0; i < numbers.size(); i++) {
            Integer score = overall.get(i);
            if (score != null && (bestScore == null || score >= bestScore)) {
                best = numbers.get(i);
                bestScore = score;
            }
        }
        return best;
    }

    private static Integer minus(Integer to, Integer from) {
        return to == null || from == null ? null : to - from;
    }

    private record Round(int number, AiReportResult report) {

        boolean partial() {
            return "partial".equals(report.reportStatus());
        }

        Integer overall() {
            return partial() || report.overall() == null ? null : report.overall().score();
        }

        Integer axis(Function<AiReportResult.Axes, AiReportResult.Axis> pick) {
            AiReportResult.Axis axis = report.axes() == null ? null : pick.apply(report.axes());
            return axis == null || !"ok".equals(axis.status()) ? null : axis.score();
        }

        /** 1회차는 재면접이 아니라 {@code is_replay} 가 false 라서, 모든 문항을 짝 맞추기 대상으로 둡니다. */
        Map<String, AiReportResult.Question> comparableQuestions() {
            Map<String, AiReportResult.Question> byId = new LinkedHashMap<>();
            Objects.requireNonNullElse(report.questions(), List.<AiReportResult.Question>of()).stream()
                    .filter(q -> number == 1 || q.isReplay())
                    .forEach(q -> byId.put(q.questionId(), q));
            return byId;
        }
    }
}
