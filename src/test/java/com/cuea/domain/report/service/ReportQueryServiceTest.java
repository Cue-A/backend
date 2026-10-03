package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.domain.report.dto.response.ReportDetailResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.infrastructure.ai.AiReportResultReader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 저장된 AI 원본이 계약 13장 모양 그대로 camelCase 응답으로 옮겨지는지,
 * 끝나지 않은 리포트는 막히는지를 봅니다.
 */
class ReportQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID PUBLIC_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess_9f2a1c";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-05T14:10:00Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-05T14:22:40Z");

    /** 리포트 계약 13장 예시. 시선 축 실패라 partial 입니다. 모르는 키(future_field)를 하나 섞었습니다. */
    private static final String CONTRACT_RESULT = """
            {
              "session_id": "sess_9f2a1c",
              "generated_at": "2026-09-05T14:22:31Z",
              "report_status": "partial",
              "future_field": "AI 가 나중에 늘린 키",
              "overall": {
                "score": 68, "display": 4, "gated": false, "gate_reason": null,
                "partial": true, "axes_used": ["content", "speech"], "axes_failed": ["gaze"]
              },
              "axes": {
                "content": { "status": "ok", "score": 72, "display": 4, "metrics": {},
                  "evidence": [ { "question_id": "q_3", "t_start": 12.4, "t_end": 19.8,
                    "kind": "weakness", "label": "근거 부족", "comment": "비교 대상이 제시되지 않았습니다." } ] },
                "speech": { "status": "ok", "score": 61, "display": 4,
                  "metrics": { "hesitation_score": 32, "speech_rate_cv": 0.284, "repetition_count": 3 },
                  "evidence": [] },
                "gaze": { "status": "failed", "error_code": "GAZE_FAILED", "score": null,
                  "display": null, "metrics": null, "evidence": [] }
              },
              "questions": [
                { "question_id": "q_1", "question_number": 1, "category": "지원동기", "difficulty": "L1",
                  "is_replay": false, "is_spare_topic": false, "score": 70, "display": 4,
                  "axes": { "content": 74, "speech": 63, "gaze": null },
                  "transcript": "저는 데이터가 쌓이고 흐르는 구조에...", "duration_sec": 46.2,
                  "word_count": 138, "was_timeout": false, "had_reask": true }
              ],
              "resilience": { "score": 58, "display": 3, "comment": "..." },
              "company_comment": "도전과 협업을 강조하는 인재상에 비추어...",
              "improved_answers": [
                { "question_id": "q_3", "original_excerpt": "낙관적 락을 썼습니다.",
                  "suggestion": "선택 이유와 대안 비교를 함께 언급하면...", "t_start": 12.4, "t_end": 19.8 }
              ]
            }
            """;

    /** Spring 의 ObjectMapper 와 같이 java.time 을 읽고, 날짜를 숫자로 쓰지 않습니다. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private ReportRepository reportRepository;
    private QuestionRepository questionRepository;
    private ReportQueryService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        questionRepository = mock(QuestionRepository.class);
        service = new ReportQueryService(reportRepository, questionRepository,
                new AiReportResultReader(objectMapper));
    }

    @Test
    void 끝난_리포트는_계약_모양을_camelCase_로_옮겨_준다() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));

        ReportDetailResponse response = service.getDetail(USER_ID, PUBLIC_ID.toString());

        assertThat(response.reportId()).isEqualTo(PUBLIC_ID.toString());
        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.status()).isEqualTo(ReportStatus.PARTIAL);
        assertThat(response.generatedAt()).isEqualTo(OffsetDateTime.parse("2026-09-05T14:22:31Z"));
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
        assertThat(response.completedAt()).isEqualTo(COMPLETED_AT);

        assertThat(response.overall().score()).isEqualTo(68);
        assertThat(response.overall().partial()).isTrue();
        assertThat(response.overall().axesFailed()).containsExactly("gaze");

        assertThat(response.axes().content().evidence()).singleElement()
                .satisfies(e -> {
                    assertThat(e.questionId()).isEqualTo("q_3");
                    assertThat(e.tStart()).isEqualTo(12.4);
                    assertThat(e.kind()).isEqualTo("weakness");
                });
        assertThat(response.axes().gaze().status()).isEqualTo("failed");
        assertThat(response.axes().gaze().errorCode()).isEqualTo("GAZE_FAILED");
        assertThat(response.axes().gaze().score()).isNull();
        assertThat(response.axes().gaze().metrics()).isNull();

        ReportDetailResponse.QuestionResult question = response.questions().get(0);
        assertThat(question.category()).isEqualTo("지원동기");
        assertThat(question.axes().gaze()).isNull();
        assertThat(question.durationSec()).isEqualTo(46.2);
        assertThat(question.hadReask()).isTrue();

        assertThat(response.resilience().score()).isEqualTo(58);
        assertThat(response.companyComment()).startsWith("도전과 협업");
        assertThat(response.improvedAnswers()).singleElement()
                .satisfies(a -> assertThat(a.originalExcerpt()).isEqualTo("낙관적 락을 썼습니다."));
    }

    /** 지표 키는 미확정이라 레코드로 못 옮기지만, 프론트에 snake_case 를 내보내지 않습니다. */
    @Test
    void metrics_는_값은_그대로_두고_키만_camelCase_로_바꾼다() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));

        ReportDetailResponse response = service.getDetail(USER_ID, PUBLIC_ID.toString());

        assertThat(response.axes().speech().metrics())
                .containsEntry("hesitationScore", 32)
                .containsEntry("speechRateCv", 0.284)
                .containsEntry("repetitionCount", 3)
                .doesNotContainKey("hesitation_score");
        assertThat(response.axes().content().metrics()).isEmpty();
    }

    @Test
    void 질문_원문을_우리_DB_에서_붙인다() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));
        when(questionRepository.findAllBySessionId(SESSION_ID)).thenReturn(List.of(
                Question.builder().sessionId(SESSION_ID).questionId("q_1").text("지원 동기를 말씀해 주세요").build()));

        ReportDetailResponse response = service.getDetail(USER_ID, PUBLIC_ID.toString());

        assertThat(response.questions().get(0).questionText()).isEqualTo("지원 동기를 말씀해 주세요");
    }

    @Test
    void 질문을_못_찾으면_원문만_비고_나머지는_그대로다() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));

        ReportDetailResponse response = service.getDetail(USER_ID, PUBLIC_ID.toString());

        assertThat(response.questions().get(0).questionText()).isNull();
        assertThat(response.questions().get(0).score()).isEqualTo(70);
    }

    /** 프론트가 받는 실제 JSON 키. 레코드의 boolean isXxx · tStart 가 이름 그대로 나가는지 봅니다. */
    @Test
    void 응답_JSON_키는_camelCase_다() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));

        JsonNode json = objectMapper.valueToTree(service.getDetail(USER_ID, PUBLIC_ID.toString()));

        JsonNode question = json.path("questions").get(0);
        assertThat(question.has("isReplay")).isTrue();
        assertThat(question.has("isSpareTopic")).isTrue();
        assertThat(question.has("hadReask")).isTrue();
        assertThat(json.path("axes").path("content").path("evidence").get(0).has("tStart")).isTrue();
        assertThat(json.path("overall").has("axesFailed")).isTrue();
        assertThat(json.has("improvedAnswers")).isTrue();
        assertThat(fieldNames(json)).noneMatch(name -> name.contains("_"));
    }

    /** 값(GAZE_FAILED 등)은 빼고 키 이름만 모읍니다. */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                names.add(entry.getKey());
                names.addAll(fieldNames(entry.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(child -> names.addAll(fieldNames(child)));
        }
        return names;
    }

    /** 카메라 미사용은 실패가 아닙니다. COMPLETED 이고 시선 축만 skipped 입니다. */
    @Test
    void 카메라_미사용이면_시선은_skipped_와_reason_을_준다() throws Exception {
        String skipped = """
                {
                  "report_status": "complete",
                  "overall": { "score": 70, "display": 4, "gated": false, "partial": false,
                               "axes_used": ["content", "speech"], "axes_failed": [] },
                  "axes": {
                    "content": { "status": "ok", "score": 72, "display": 4, "metrics": {}, "evidence": [] },
                    "speech":  { "status": "ok", "score": 61, "display": 4, "metrics": {}, "evidence": [] },
                    "gaze":    { "status": "skipped", "reason": "no_video", "score": null,
                                 "display": null, "metrics": null, "evidence": [] }
                  },
                  "questions": [], "resilience": null, "company_comment": null, "improved_answers": []
                }
                """;
        givenReport(finished(ReportStatus.COMPLETED, skipped));

        ReportDetailResponse response = service.getDetail(USER_ID, PUBLIC_ID.toString());

        assertThat(response.status()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(response.axes().gaze().status()).isEqualTo("skipped");
        assertThat(response.axes().gaze().reason()).isEqualTo("no_video");
        assertThat(response.axes().gaze().errorCode()).isNull();
        assertThat(response.resilience()).isNull();
        assertThat(response.companyComment()).isNull();
    }

    /** 글로 된 칸의 키가 아예 빠져도 null 이 아니라 빈 배열로 내려줍니다. */
    @Test
    void 배열_키가_빠져도_빈_배열이다() throws Exception {
        givenReport(finished(ReportStatus.COMPLETED, """
                { "report_status": "complete", "overall": { "score": 50 }, "axes": {} }
                """));

        ReportDetailResponse response = service.getDetail(USER_ID, PUBLIC_ID.toString());

        assertThat(response.questions()).isEmpty();
        assertThat(response.improvedAnswers()).isEmpty();
        assertThat(response.overall().axesUsed()).isEmpty();
    }

    @Test
    void 분석_중이면_REPORT_NOT_READY() {
        givenReport(report(ReportStatus.PROCESSING, null, null));

        assertErrorCode(ErrorCode.REPORT_NOT_READY);
    }

    @Test
    void 실패한_리포트면_REPORT_FAILED() {
        givenReport(report(ReportStatus.FAILED, null, COMPLETED_AT));

        assertErrorCode(ErrorCode.REPORT_FAILED);
    }

    /** 남의 리포트도 소유자 조건이 걸린 조회에서 빈 값이라 여기로 옵니다. */
    @Test
    void 없거나_남의_리포트면_REPORT_NOT_FOUND() {
        when(reportRepository.findByPublicIdAndSession_User_UserId(any(), anyString()))
                .thenReturn(Optional.empty());

        assertErrorCode(ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    void UUID_가_아니면_조회하지_않고_REPORT_NOT_FOUND() {
        assertThatThrownBy(() -> service.getDetail(USER_ID, "not-a-uuid"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
        verify(reportRepository, never()).findByPublicIdAndSession_User_UserId(any(), anyString());
    }

    @Test
    void 저장된_원본이_계약_모양이_아니면_UNEXPECTED_AI_RESPONSE() throws Exception {
        givenReport(finished(ReportStatus.COMPLETED, """
                { "report_status": "complete", "questions": "배열이어야 하는 자리" }
                """));

        assertErrorCode(ErrorCode.UNEXPECTED_AI_RESPONSE);
    }

    private void assertErrorCode(ErrorCode expected) {
        assertThatThrownBy(() -> service.getDetail(USER_ID, PUBLIC_ID.toString()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private void givenReport(Report report) {
        when(reportRepository.findByPublicIdAndSession_User_UserId(PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(report));
    }

    private Report finished(ReportStatus status, String resultJson) throws Exception {
        Map<String, Object> reportData = objectMapper.readValue(resultJson, new TypeReference<>() {
        });
        return report(status, reportData, COMPLETED_AT);
    }

    private Report report(ReportStatus status, Map<String, Object> reportData, OffsetDateTime completedAt) {
        InterviewSession session = InterviewSession.builder().sessionId(SESSION_ID).build();
        return Report.builder().reportId(10L).publicId(PUBLIC_ID).session(session)
                .status(status).attempt(1).reportData(reportData)
                .createdAt(CREATED_AT).completedAt(completedAt).build();
    }
}
