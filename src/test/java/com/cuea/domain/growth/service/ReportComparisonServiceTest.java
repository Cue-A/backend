package com.cuea.domain.growth.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.growth.dto.response.ReportComparisonResponse;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.interview.service.SessionRound;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.service.FinishedReport;
import com.cuea.domain.report.service.FinishedReportQueryService;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiReportCompareRequest;
import com.cuea.infrastructure.ai.dto.AiReportComparison;
import com.cuea.support.JsonFieldNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 비교할 회차를 고르는 규칙(이 리포트의 회차까지, 리포트가 끝난 회차만, 회차 번호는 연습 기록 그대로)과
 * AI 응답을 옮기는 방법(질문 원문 · 회차별 리포트 정보를 붙이고 자리표시는 뺌)을 봅니다.
 * 회차 번호 자체는 {@code InterviewSessionQueryServiceTest} 가 봅니다.
 */
class ReportComparisonServiceTest {

    private static final String USER_ID = "user-1";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-01T10:00:00Z");

    private static final FinishedReport FIRST = finished("rpt-1", "sess_1", ReportStatus.COMPLETED);
    private static final FinishedReport THIRD = finished("rpt-3", "sess_3", ReportStatus.PARTIAL);

    /** Spring 의 ObjectMapper 와 같이 java.time 을 읽고, 날짜를 숫자로 쓰지 않습니다. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private FinishedReportQueryService reportQueryService;
    private InterviewSessionQueryService sessionQueryService;
    private AiClient aiClient;
    private ReportComparisonService service;

    @BeforeEach
    void setUp() {
        reportQueryService = mock(FinishedReportQueryService.class);
        sessionQueryService = mock(InterviewSessionQueryService.class);
        aiClient = mock(AiClient.class);
        service = new ReportComparisonService(reportQueryService, sessionQueryService, aiClient);
    }

    /** 2회차는 리포트가 없어 빠지고, 번호는 다시 매기지 않고 연습 기록의 회차 그대로 보냅니다. */
    @Test
    void 이_리포트의_회차까지_리포트가_끝난_회차만_회차_번호_그대로_보낸다() {
        givenRounds(THIRD, List.of(round("sess_1", 1), round("sess_2", 2), round("sess_3", 3)), FIRST, THIRD);
        when(aiClient.compareReports(any())).thenReturn(comparison(List.of(1, 3)));

        ReportComparisonResponse response = service.compare(USER_ID, "rpt-3");

        ArgumentCaptor<AiReportCompareRequest> sent = ArgumentCaptor.forClass(AiReportCompareRequest.class);
        verify(aiClient).compareReports(sent.capture());
        assertThat(sent.getValue().reports()).containsExactly(
                new AiReportCompareRequest.Round("sess_1", 1, FIRST.reportData()),
                new AiReportCompareRequest.Round("sess_3", 3, THIRD.reportData()));

        assertThat(response.reportId()).isEqualTo("rpt-3");
        assertThat(response.latestRound()).isEqualTo(3);
        assertThat(response.comparedRounds()).containsExactly(1, 3);
        assertThat(response.rounds()).containsExactly(
                new ReportComparisonResponse.Round(1, "rpt-1", ReportStatus.COMPLETED, T0.plusDays(1)),
                new ReportComparisonResponse.Round(3, "rpt-3", ReportStatus.PARTIAL, T0.plusDays(3)));
    }

    @Test
    void AI_응답을_옮기고_질문_원문은_이_리포트_세션에서_찾아_붙인다() {
        givenRounds(THIRD, List.of(round("sess_1", 1), round("sess_3", 3)), FIRST, THIRD);
        when(sessionQueryService.findQuestionTextsForInternal("sess_3")).thenReturn(Map.of("q_1", "지원 동기를 말씀해 주세요"));
        when(aiClient.compareReports(any())).thenReturn(comparison(List.of(1, 3)));

        ReportComparisonResponse response = service.compare(USER_ID, "rpt-3");

        assertThat(response.partialRounds()).containsExactly(3);
        assertThat(response.vsPrevious().fromRound()).isEqualTo(1);
        assertThat(response.vsPrevious().toRound()).isEqualTo(3);
        assertThat(response.vsPrevious().overallDelta()).isNull();
        assertThat(response.vsPrevious().axisDelta().content()).isEqualTo(8);
        assertThat(response.vsPrevious().improved()).containsExactly("q_1");
        assertThat(response.trend().overall()).containsExactly(68, null);
        assertThat(response.trend().axes().gaze()).containsExactly(55, null);
        assertThat(response.trend().stalledAxes()).containsExactly("speech");
        assertThat(response.trend().bestRound()).isEqualTo(1);
        assertThat(response.byQuestion()).extracting(ReportComparisonResponse.QuestionChange::questionText)
                .containsExactly("지원 동기를 말씀해 주세요", null);
        assertThat(response.byQuestion().getFirst().category()).isEqualTo("지원동기");
        assertThat(response.byQuestion().getFirst().scores()).containsExactly(62, 75);
    }

    /** 프론트가 받는 실제 JSON. AI 의 자리표시(text)는 내보내지 않고 snake_case 키가 없습니다. */
    @Test
    void 응답_JSON_키는_camelCase_이고_자리표시는_없다() {
        givenRounds(THIRD, List.of(round("sess_1", 1), round("sess_3", 3)), FIRST, THIRD);
        when(aiClient.compareReports(any())).thenReturn(comparison(List.of(1, 3)));

        JsonNode json = objectMapper.valueToTree(service.compare(USER_ID, "rpt-3"));

        assertThat(json.path("vsPrevious").has("fromRound")).isTrue();
        assertThat(json.path("byQuestion").get(0).has("questionText")).isTrue();
        assertThat(json.path("byQuestion").get(0).has("text")).isFalse();
        assertThat(json.path("rounds").get(0).path("createdAt").asText()).isEqualTo("2026-10-02T10:00:00Z");
        assertThat(JsonFieldNames.of(json)).noneMatch(name -> name.contains("_"));
    }

    /** 회차가 하나여도 오류가 아닙니다. 추이 점 하나는 그릴 수 있어 AI 를 부릅니다. */
    @Test
    void 회차가_하나면_직전_비교는_null_이고_배열_칸이_빠져도_빈_배열이다() {
        givenRounds(FIRST, List.of(round("sess_1", 1)), FIRST);
        when(aiClient.compareReports(any())).thenReturn(new AiReportComparison(1, List.of(1), null, null,
                new AiReportComparison.Trend(List.of(68), null, null, null, 1), null));

        ReportComparisonResponse response = service.compare(USER_ID, "rpt-1");

        assertThat(response.latestRound()).isEqualTo(1);
        assertThat(response.vsPrevious()).isNull();
        assertThat(response.partialRounds()).isEmpty();
        assertThat(response.byQuestion()).isEmpty();
        assertThat(response.trend().stalledAxes()).isEmpty();
        assertThat(response.trend().axes()).isEqualTo(new ReportComparisonResponse.AxisTrend(null, null, null));
    }

    /** 배열 칸과 회차를 맞출 수 없으면 그래프 점에 다른 회차를 걸게 됩니다. */
    @Test
    void AI_가_보낸_회차와_다른_회차를_주면_UNEXPECTED_AI_RESPONSE() {
        givenRounds(THIRD, List.of(round("sess_1", 1), round("sess_3", 3)), FIRST, THIRD);
        when(aiClient.compareReports(any())).thenReturn(comparison(List.of(1, 2)));

        assertErrorCode(ErrorCode.UNEXPECTED_AI_RESPONSE);
    }

    @Test
    void 추이가_없으면_UNEXPECTED_AI_RESPONSE() {
        givenRounds(THIRD, List.of(round("sess_1", 1), round("sess_3", 3)), FIRST, THIRD);
        when(aiClient.compareReports(any())).thenReturn(
                new AiReportComparison(3, List.of(1, 3), List.of(), null, null, List.of()));

        assertErrorCode(ErrorCode.UNEXPECTED_AI_RESPONSE);
    }

    @Test
    void AI_가_빈_응답을_주면_UNEXPECTED_AI_RESPONSE() {
        givenRounds(THIRD, List.of(round("sess_1", 1), round("sess_3", 3)), FIRST, THIRD);

        assertErrorCode(ErrorCode.UNEXPECTED_AI_RESPONSE);
    }

    /** 요청은 Backend 가 조립하므로 AI 의 400 은 사용자 잘못이 아닙니다. AI 문구는 사용자에게 내보내지 않습니다. */
    @Test
    void AI_가_거절하면_502_이고_AI_문구를_내보내지_않는다() {
        givenRounds(THIRD, List.of(round("sess_1", 1), round("sess_3", 3)), FIRST, THIRD);
        when(aiClient.compareReports(any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_REQUEST, "duplicate round: 3"));

        assertThatThrownBy(() -> service.compare(USER_ID, "rpt-3"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.UNEXPECTED_AI_RESPONSE);
                    assertThat(e.getMessage()).isEqualTo(ErrorCode.UNEXPECTED_AI_RESPONSE.getMessage());
                });
    }

    @Test
    void AI_에_연결하지_못하면_AI_UNAVAILABLE_그대로다() {
        givenRounds(THIRD, List.of(round("sess_1", 1), round("sess_3", 3)), FIRST, THIRD);
        when(aiClient.compareReports(any())).thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));

        assertErrorCode(ErrorCode.AI_UNAVAILABLE);
    }

    /** 202 · 409 · 404 는 리포트 쪽 규칙 그대로이고, 그때는 AI 를 부르지 않습니다. */
    @Test
    void 리포트가_끝나지_않았으면_AI_를_부르지_않는다() {
        when(reportQueryService.getFinished(USER_ID, "rpt-3"))
                .thenThrow(new BusinessException(ErrorCode.REPORT_NOT_READY));

        assertErrorCode(ErrorCode.REPORT_NOT_READY);
        verify(aiClient, never()).compareReports(any());
    }

    private void assertErrorCode(ErrorCode expected) {
        assertThatThrownBy(() -> service.compare(USER_ID, "rpt-3"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }

    private void givenRounds(FinishedReport target, List<SessionRound> rounds, FinishedReport... finished) {
        when(reportQueryService.getFinished(USER_ID, target.reportId())).thenReturn(target);
        when(sessionQueryService.roundsUpTo(USER_ID, target.sessionId())).thenReturn(rounds);
        Map<String, FinishedReport> bySession = new HashMap<>();
        for (FinishedReport report : finished) {
            bySession.put(report.sessionId(), report);
        }
        List<String> sessionIds = rounds.stream().map(SessionRound::sessionId).toList();
        when(reportQueryService.findFinishedBySessionIds(USER_ID, sessionIds)).thenReturn(bySession);
    }

    private static SessionRound round(String sessionId, int round) {
        return new SessionRound(sessionId, round, T0.plusDays(round));
    }

    private static FinishedReport finished(String reportId, String sessionId, ReportStatus status) {
        return new FinishedReport(reportId, sessionId, status, Map.of("session_id", sessionId));
    }

    /** AI 답변의 실제 응답 예시와 같은 모양. 3회차가 부분 리포트라 종합 변화가 null 입니다. */
    private static AiReportComparison comparison(List<Integer> comparedRounds) {
        return new AiReportComparison(
                comparedRounds.getLast(),
                comparedRounds,
                List.of(3),
                new AiReportComparison.VsPrevious(1, 3, null,
                        new AiReportComparison.AxisDelta(8, -2, null),
                        List.of("q_1"), List.of("q_2"), List.of(), "근거를 수치로 제시하는 답변이 늘었습니다."),
                new AiReportComparison.Trend(
                        Arrays.asList(68, null),
                        new AiReportComparison.AxisTrend(List.of(70, 78), List.of(61, 59),
                                Arrays.asList(55, null)),
                        "내용 점수가 꾸준히 오르고 있습니다.", List.of("speech"), 1),
                List.of(
                        new AiReportComparison.QuestionChange("q_1", "지원동기", List.of(62, 75), 13, 13,
                                "회사와 직무를 연결하는 설명이 구체적이 되었습니다."),
                        new AiReportComparison.QuestionChange("q_2", "직무역량", List.of(70, 64), -6, -6,
                                "근거가 줄었습니다.")));
    }
}
