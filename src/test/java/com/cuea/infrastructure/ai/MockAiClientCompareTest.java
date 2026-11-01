package com.cuea.infrastructure.ai;

import com.cuea.infrastructure.ai.dto.AiReportCompareRequest;
import com.cuea.infrastructure.ai.dto.AiReportComparison;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로컬 기본값이 mock 이라 회차 비교 화면도 mock 으로 먼저 봅니다. AI 답변으로 확인한 규칙
 * (부분 리포트 · 실패 축은 null, 1회차 문항 짝 맞추기, 회차가 하나면 비교 없음)을 따르는지 봅니다.
 */
class MockAiClientCompareTest {

    private final MockAiClient client = new MockAiClient();

    @Test
    void 회차가_하나면_직전_비교는_null_이고_문항_비교는_비어_있다() {
        AiReportComparison result = client.compareReports(request(round(1, report("complete", 68, 61, List.of()))));

        assertThat(result.latestRound()).isEqualTo(1);
        assertThat(result.comparedRounds()).containsExactly(1);
        assertThat(result.vsPrevious()).isNull();
        assertThat(result.byQuestion()).isEmpty();
        assertThat(result.trend().overall()).containsExactly(68);
        assertThat(result.trend().bestRound()).isEqualTo(1);
    }

    /** 회차 번호는 보낸 그대로이고, 비교는 배열에서 바로 앞 회차와 합니다(3 vs 1). */
    @Test
    void 부분_리포트_회차는_종합_점수와_종합_변화가_null_이다() {
        AiReportComparison result = client.compareReports(request(
                round(1, report("complete", 68, 61, List.of())),
                round(3, report("partial", 74, null, List.of()))));

        assertThat(result.comparedRounds()).containsExactly(1, 3);
        assertThat(result.partialRounds()).containsExactly(3);
        assertThat(result.trend().overall()).containsExactly(68, null);
        assertThat(result.trend().axes().speech()).containsExactly(61, null);
        assertThat(result.trend().bestRound()).isEqualTo(1);
        assertThat(result.vsPrevious().fromRound()).isEqualTo(1);
        assertThat(result.vsPrevious().toRound()).isEqualTo(3);
        assertThat(result.vsPrevious().overallDelta()).isNull();
        assertThat(result.vsPrevious().axisDelta().content()).isEqualTo(0);
        assertThat(result.vsPrevious().axisDelta().speech()).isNull();
    }

    /** 1회차 문항은 is_replay 가 false 지만 짝 맞추기 대상입니다. 재면접의 대체 질문(is_replay false)은 빠집니다. */
    @Test
    void 모든_회차에_있는_재면접_주질문만_문항별로_비교한다() {
        AiReportComparison result = client.compareReports(request(
                round(1, report("complete", 68, 61, List.of(
                        question("q_1", false, 62), question("q_2", false, 50), question("q_3", false, 70)))),
                round(2, report("complete", 72, 63, List.of(
                        question("q_1", true, 70), question("q_2", false, 80), question("q_3", true, 65))))));

        assertThat(result.byQuestion()).extracting(AiReportComparison.QuestionChange::questionId)
                .containsExactly("q_1", "q_3");
        assertThat(result.byQuestion().getFirst().scores()).containsExactly(62, 70);
        assertThat(result.byQuestion().getFirst().deltaFromFirst()).isEqualTo(8);
        assertThat(result.byQuestion().getFirst().category()).isEqualTo("지원동기");
        assertThat(result.vsPrevious().improved()).containsExactly("q_1");
        assertThat(result.vsPrevious().declined()).containsExactly("q_3");
        assertThat(result.vsPrevious().overallDelta()).isEqualTo(4);
    }

    private static AiReportCompareRequest request(AiReportCompareRequest.Round... rounds) {
        return new AiReportCompareRequest(Arrays.asList(rounds));
    }

    private static AiReportCompareRequest.Round round(int number, Map<String, Object> report) {
        return new AiReportCompareRequest.Round("sess_" + number, number, report);
    }

    /** 저장된 원본처럼 snake_case 키의 Map 입니다. speech 가 null 이면 그 축이 실패한 것입니다. */
    private static Map<String, Object> report(String status, int overall, Integer speech, List<Map<String, Object>> questions) {
        Map<String, Object> report = new HashMap<>();
        report.put("report_status", status);
        report.put("overall", Map.of("score", overall));
        report.put("axes", Map.of(
                "content", Map.of("status", "ok", "score", 70),
                "speech", speech == null ? Map.of("status", "failed") : Map.of("status", "ok", "score", speech),
                "gaze", Map.of("status", "skipped")));
        report.put("questions", questions);
        return report;
    }

    private static Map<String, Object> question(String questionId, boolean replay, int score) {
        return Map.of("question_id", questionId, "category", "지원동기", "is_replay", replay, "score", score);
    }
}
