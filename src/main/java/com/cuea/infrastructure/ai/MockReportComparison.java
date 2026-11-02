package com.cuea.infrastructure.ai;

import com.cuea.infrastructure.ai.dto.AiReportCompareRequest;
import com.cuea.infrastructure.ai.dto.AiReportComparison;
import com.cuea.infrastructure.ai.dto.AiReportComparison.AxisDelta;
import com.cuea.infrastructure.ai.dto.AiReportComparison.AxisTrend;
import com.cuea.infrastructure.ai.dto.AiReportComparison.QuestionChange;
import com.cuea.infrastructure.ai.dto.AiReportComparison.Trend;
import com.cuea.infrastructure.ai.dto.AiReportComparison.VsPrevious;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import com.cuea.infrastructure.ai.dto.AiReportResult.Axes;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntPredicate;

/**
 * {@link MockAiClient} 의 회차 비교. 보낸 리포트 원본의 점수만으로 실제 AI 와 같은 규칙으로 비교하고
 * 코멘트는 고정 문구입니다. 규칙은 docs/10-ai-client.md 의 개발용 목 절에 있습니다.
 */
final class MockReportComparison {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final String COMMENT = "목 응답입니다.";

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
        Trend trend = new Trend(overall,
                new AxisTrend(axisTrend(rounds, Axes::content), axisTrend(rounds, Axes::speech),
                        axisTrend(rounds, Axes::gaze)),
                COMMENT, List.of(), bestRound(numbers, overall));

        return new AiReportComparison(numbers.getLast(), numbers,
                rounds.stream().filter(Round::partial).map(Round::number).toList(),
                vsPrevious(rounds, byQuestion), trend, byQuestion);
    }

    /** 배열에서 바로 앞 회차와 비교합니다. */
    private static VsPrevious vsPrevious(List<Round> rounds, List<QuestionChange> byQuestion) {
        if (rounds.size() < 2) {
            return null;
        }
        Round from = rounds.get(rounds.size() - 2);
        Round to = rounds.getLast();
        AxisDelta axisDelta = new AxisDelta(
                minus(to.axis(Axes::content), from.axis(Axes::content)),
                minus(to.axis(Axes::speech), from.axis(Axes::speech)),
                minus(to.axis(Axes::gaze), from.axis(Axes::gaze)));
        return new VsPrevious(from.number(), to.number(), minus(to.overall(), from.overall()), axisDelta,
                questionIds(byQuestion, d -> d > 0), questionIds(byQuestion, d -> d < 0),
                questionIds(byQuestion, d -> d == 0), COMMENT);
    }

    /** 첫 회차의 문항 순서대로, 비교한 모든 회차에 있는 문항만. 회차가 하나면 비교하지 않습니다. */
    private static List<QuestionChange> byQuestion(List<Round> rounds) {
        if (rounds.size() < 2) {
            return List.of();
        }
        List<Map<String, AiReportResult.Question>> comparable = rounds.stream().map(Round::comparable).toList();
        return comparable.getFirst().values().stream()
                .filter(q -> comparable.stream().allMatch(byId -> byId.containsKey(q.questionId())))
                .map(q -> {
                    List<Integer> scores = comparable.stream().map(byId -> byId.get(q.questionId()).score()).toList();
                    return new QuestionChange(q.questionId(), q.category(), scores,
                            minus(scores.getLast(), scores.get(scores.size() - 2)),
                            minus(scores.getLast(), scores.getFirst()), COMMENT);
                })
                .toList();
    }

    private static List<String> questionIds(List<QuestionChange> byQuestion, IntPredicate delta) {
        return byQuestion.stream()
                .filter(q -> q.deltaFromPrevious() != null && delta.test(q.deltaFromPrevious()))
                .map(QuestionChange::questionId)
                .toList();
    }

    private static List<Integer> axisTrend(List<Round> rounds, Function<Axes, AiReportResult.Axis> axis) {
        return rounds.stream().map(r -> r.axis(axis)).toList();
    }

    /** 종합 점수가 있는 회차 중 최고(같으면 나중 회차). 전부 없으면 마지막 회차입니다. */
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

        /** 부분 리포트는 총점 스케일이 달라 null 입니다. */
        Integer overall() {
            return partial() || report.overall() == null ? null : report.overall().score();
        }

        /** 실패 · 미사용 축(status 가 ok 가 아님)은 null. */
        Integer axis(Function<Axes, AiReportResult.Axis> pick) {
            AiReportResult.Axis axis = report.axes() == null ? null : pick.apply(report.axes());
            return axis == null || !"ok".equals(axis.status()) ? null : axis.score();
        }

        /** 짝 맞출 문항. 1회차는 is_replay 가 false 라 모든 문항, 재면접은 is_replay 문항입니다. */
        Map<String, AiReportResult.Question> comparable() {
            Map<String, AiReportResult.Question> byId = new LinkedHashMap<>();
            Objects.requireNonNullElse(report.questions(), List.<AiReportResult.Question>of()).stream()
                    .filter(q -> number == 1 || q.isReplay())
                    .forEach(q -> byId.put(q.questionId(), q));
            return byId;
        }
    }
}
