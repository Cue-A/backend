package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.report.dto.response.ReportDetailResponse;
import com.cuea.domain.report.dto.response.ReportImprovementsResponse;
import com.cuea.domain.report.dto.response.ReportTimelineResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportRetryStatus;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.infrastructure.ai.AiReportResultReader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import static org.assertj.core.api.Assertions.tuple;
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

    /**
     * 되묻기가 있는 결과. q_1 의 근거가 원 답변(q_1)과 되묻기 답변(q_1r)에 나뉘어 있고,
     * q_3 은 근거가 없습니다. 시선 근거는 내용 근거보다 앞에 둔 순서를 섞었습니다.
     */
    private static final String REASK_RESULT = """
            {
              "report_status": "complete",
              "overall": { "score": 72, "display": 4, "gated": false, "partial": false,
                           "axes_used": ["content", "speech", "gaze"], "axes_failed": [] },
              "axes": {
                "content": { "status": "ok", "score": 72, "display": 4, "metrics": {}, "evidence": [
                  { "question_id": "q_1r", "t_start": 3.1, "t_end": 8.0, "kind": "strength",
                    "label": "구체적 사례", "comment": "되묻기에서 사례를 보완했습니다." },
                  { "question_id": "q_1", "t_start": 12.4, "t_end": 19.8, "kind": "weakness",
                    "label": "근거 부족", "comment": "결과 수치가 없습니다." },
                  { "question_id": "q_2", "t_start": 5.0, "t_end": 9.0, "kind": "weakness",
                    "label": "논리 비약", "comment": "..." } ] },
                "speech": { "status": "ok", "score": 61, "display": 4, "metrics": {}, "evidence": [] },
                "gaze": { "status": "ok", "score": 70, "display": 4, "metrics": {}, "evidence": [
                  { "question_id": "q_1", "t_start": 1.0, "t_end": 2.5, "kind": "weakness",
                    "label": "시선 회피", "comment": "..." } ] }
              },
              "questions": [
                { "question_id": "q_1", "question_number": 1, "duration_sec": 46.2, "had_reask": true },
                { "question_id": "q_2", "question_number": 2, "duration_sec": 52.0, "had_reask": false },
                { "question_id": "q_3", "question_number": 3, "duration_sec": 30.0, "had_reask": false }
              ],
              "resilience": null,
              "improved_answers": []
            }
            """;

    /** Spring 의 ObjectMapper 와 같이 java.time 을 읽고, 날짜를 숫자로 쓰지 않습니다. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private ReportRepository reportRepository;
    private InterviewSessionQueryService sessionQueryService;
    private ReportQueryService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        sessionQueryService = mock(InterviewSessionQueryService.class);
        service = new ReportQueryService(new ReportFinder(reportRepository), sessionQueryService,
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
        when(sessionQueryService.findQuestionsInOrderForInternal(SESSION_ID)).thenReturn(List.of(
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

    /** 재시도 중에도 리포트는 그대로 200 으로 내려가고, 다시 분석하는 축만 retry 로 알려줍니다. */
    @Test
    void 재시도_중에도_리포트를_그대로_주고_retry_에_축을_준다() throws Exception {
        Report report = finished(ReportStatus.PARTIAL, CONTRACT_RESULT);
        givenReport(Report.builder().reportId(10L).publicId(PUBLIC_ID).session(report.getSession())
                .status(ReportStatus.PARTIAL).retryStatus(ReportRetryStatus.PROCESSING).attempt(2)
                .reportData(report.getReportData()).createdAt(CREATED_AT).completedAt(COMPLETED_AT).build());

        ReportDetailResponse response = service.getDetail(USER_ID, PUBLIC_ID.toString());

        assertThat(response.overall().score()).isEqualTo(68);
        assertThat(response.retry().status()).isEqualTo(ReportRetryStatus.PROCESSING);
        assertThat(response.retry().axes()).containsExactly("gaze");
    }

    @Test
    void 재시도한_적이_없으면_retry_는_null() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));

        assertThat(service.getDetail(USER_ID, PUBLIC_ID.toString()).retry()).isNull();
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
    void 저장된_원본을_읽지_못하면_우리_쪽_문제라_INTERNAL_ERROR() throws Exception {
        givenReport(finished(ReportStatus.COMPLETED, """
                { "report_status": "complete", "questions": "배열이어야 하는 자리" }
                """));

        assertErrorCode(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void metrics_안쪽_객체의_키도_camelCase_로_바꾼다() throws Exception {
        givenReport(finished(ReportStatus.COMPLETED, """
                { "report_status": "complete",
                  "axes": { "speech": { "status": "ok",
                    "metrics": { "per_answer": [ { "filler_count": 2 } ], "rate_detail": { "words_per_min": 120 } } } } }
                """));

        ReportDetailResponse response = service.getDetail(USER_ID, PUBLIC_ID.toString());

        assertThat(response.axes().speech().metrics())
                .containsEntry("perAnswer", List.of(Map.of("fillerCount", 2)))
                .containsEntry("rateDetail", Map.of("wordsPerMin", 120));
    }

    // --- 타임라인 ---

    /** 되묻기 근거는 되묻기 ID 로 오지만 원 질문 아래에 들어가고, 원 답변 근거 뒤에 섭니다. */
    @Test
    void 타임라인은_근거를_원_질문별로_묶고_되묻기_근거는_원_질문_아래에_둔다() throws Exception {
        givenReport(finished(ReportStatus.COMPLETED, REASK_RESULT));
        givenQuestionsInDb();

        ReportTimelineResponse response = service.getTimeline(USER_ID, PUBLIC_ID.toString());

        ReportTimelineResponse.TimelineQuestion first = response.questions().get(0);
        assertThat(first.questionId()).isEqualTo("q_1");
        assertThat(first.questionText()).isEqualTo("지원 동기를 말씀해 주세요");
        assertThat(first.durationSec()).isEqualTo(46.2);
        assertThat(first.hadReask()).isTrue();
        assertThat(first.evidence())
                .extracting("axis", "questionId", "isReask", "tStart")
                .containsExactly(
                        tuple("gaze", "q_1", false, 1.0),
                        tuple("content", "q_1", false, 12.4),
                        tuple("content", "q_1r", true, 3.1));
        assertThat(response.questions().get(1).evidence()).singleElement()
                .satisfies(e -> assertThat(e.questionId()).isEqualTo("q_2"));
    }

    /** 타임라인 구간을 그려야 하므로 근거가 없는 문항도 빠지지 않습니다. */
    @Test
    void 타임라인은_근거가_없는_문항도_빈_배열로_준다() throws Exception {
        givenReport(finished(ReportStatus.COMPLETED, REASK_RESULT));
        givenQuestionsInDb();

        ReportTimelineResponse response = service.getTimeline(USER_ID, PUBLIC_ID.toString());

        assertThat(response.questions()).extracting(ReportTimelineResponse.TimelineQuestion::questionId)
                .containsExactly("q_1", "q_2", "q_3");
        assertThat(response.questions().get(2).evidence()).isEmpty();
        assertThat(response.questions().get(2).durationSec()).isEqualTo(30.0);
    }

    /** 근거가 빈 이유(근거 없음 · 분석 실패 · 카메라 미사용)를 축 상태로 구분할 수 있어야 합니다. */
    @Test
    void 타임라인은_축_상태와_회복력을_준다() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));

        ReportTimelineResponse response = service.getTimeline(USER_ID, PUBLIC_ID.toString());

        assertThat(response.status()).isEqualTo(ReportStatus.PARTIAL);
        assertThat(response.axes().content()).isEqualTo("ok");
        assertThat(response.axes().gaze()).isEqualTo("failed");
        assertThat(response.resilience().score()).isEqualTo(58);
        assertThat(response.retry()).isNull();
    }

    /** 계약 예시의 q_3 근거는 questions[] 에 q_3 이 없어 붙을 문항이 없습니다. */
    @Test
    void 타임라인에서_문항을_찾지_못한_근거는_빠진다() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));

        ReportTimelineResponse response = service.getTimeline(USER_ID, PUBLIC_ID.toString());

        assertThat(response.questions()).singleElement()
                .satisfies(q -> assertThat(q.evidence()).isEmpty());
    }

    @Test
    void 타임라인_재시도_중이면_retry_에_축을_준다() throws Exception {
        Report report = finished(ReportStatus.PARTIAL, CONTRACT_RESULT);
        givenReport(Report.builder().reportId(10L).publicId(PUBLIC_ID).session(report.getSession())
                .status(ReportStatus.PARTIAL).retryStatus(ReportRetryStatus.PROCESSING).attempt(2)
                .reportData(report.getReportData()).createdAt(CREATED_AT).completedAt(COMPLETED_AT).build());

        ReportTimelineResponse response = service.getTimeline(USER_ID, PUBLIC_ID.toString());

        assertThat(response.retry().status()).isEqualTo(ReportRetryStatus.PROCESSING);
        assertThat(response.retry().axes()).containsExactly("gaze");
    }

    @Test
    void 타임라인_응답_JSON_키는_camelCase_다() throws Exception {
        givenReport(finished(ReportStatus.COMPLETED, REASK_RESULT));
        givenQuestionsInDb();

        JsonNode json = objectMapper.valueToTree(service.getTimeline(USER_ID, PUBLIC_ID.toString()));

        JsonNode evidence = json.path("questions").get(0).path("evidence").get(2);
        assertThat(evidence.path("isReask").asBoolean()).isTrue();
        assertThat(evidence.has("tStart")).isTrue();
        assertThat(json.path("questions").get(0).has("hadReask")).isTrue();
        assertThat(fieldNames(json)).noneMatch(name -> name.contains("_"));
    }

    @Test
    void 타임라인도_분석_중이면_REPORT_NOT_READY_실패면_REPORT_FAILED() {
        givenReport(report(ReportStatus.PROCESSING, null, null));
        assertErrorCode(() -> service.getTimeline(USER_ID, PUBLIC_ID.toString()), ErrorCode.REPORT_NOT_READY);

        givenReport(report(ReportStatus.FAILED, null, COMPLETED_AT));
        assertErrorCode(() -> service.getTimeline(USER_ID, PUBLIC_ID.toString()), ErrorCode.REPORT_FAILED);
    }

    // --- 개선 답변 ---

    /** 되묻기 답변에서 따온 발췌는 되묻기 ID 로 오지만, 문항 번호와 원문은 원 질문 것을 붙입니다. */
    @Test
    void 개선_답변은_되묻기에서_발췌했으면_원_질문을_붙인다() throws Exception {
        givenReport(finished(ReportStatus.COMPLETED, improvements("""
                { "question_id": "q_1r", "original_excerpt": "그냥 열심히 했습니다.",
                  "suggestion": "맡은 역할을 먼저 말해 보세요.", "t_start": 3.1, "t_end": 8.0 },
                { "question_id": "q_2", "original_excerpt": "같이 잘 해결했습니다.",
                  "suggestion": "갈등의 원인을 구체적으로 말해 보세요.", "t_start": 5.0, "t_end": 9.0 }
                """)));
        givenQuestionsInDb();

        ReportImprovementsResponse response = service.getImprovements(USER_ID, PUBLIC_ID.toString());

        assertThat(response.reportId()).isEqualTo(PUBLIC_ID.toString());
        assertThat(response.improvedAnswers())
                .extracting("questionId", "originalQuestionId", "isReask", "questionNumber", "questionText")
                .containsExactly(
                        tuple("q_1r", "q_1", true, 1, "지원 동기를 말씀해 주세요"),
                        tuple("q_2", "q_2", false, 2, "가장 어려웠던 협업 경험은 무엇인가요?"));
        assertThat(response.improvedAnswers().get(0))
                .satisfies(a -> {
                    assertThat(a.originalExcerpt()).isEqualTo("그냥 열심히 했습니다.");
                    assertThat(a.suggestion()).isEqualTo("맡은 역할을 먼저 말해 보세요.");
                    assertThat(a.tStart()).isEqualTo(3.1);
                    assertThat(a.tEnd()).isEqualTo(8.0);
                });
    }

    @Test
    void 개선_답변의_질문을_못_찾으면_번호와_원문만_비고_나머지는_그대로다() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));

        ReportImprovementsResponse response = service.getImprovements(USER_ID, PUBLIC_ID.toString());

        assertThat(response.improvedAnswers()).singleElement()
                .satisfies(a -> {
                    assertThat(a.questionId()).isEqualTo("q_3");
                    assertThat(a.originalQuestionId()).isEqualTo("q_3");
                    assertThat(a.isReask()).isFalse();
                    assertThat(a.questionNumber()).isNull();
                    assertThat(a.questionText()).isNull();
                    assertThat(a.originalExcerpt()).isEqualTo("낙관적 락을 썼습니다.");
                });
    }

    /** 모두 잘 답했거나 생성에 실패하면 AI 가 빈 배열을 주고, 키가 빠져도 빈 배열로 내려줍니다. */
    @Test
    void 개선_답변이_없으면_빈_배열이다() throws Exception {
        givenReport(finished(ReportStatus.COMPLETED, """
                { "report_status": "complete", "overall": { "score": 50 }, "axes": {} }
                """));

        assertThat(service.getImprovements(USER_ID, PUBLIC_ID.toString()).improvedAnswers()).isEmpty();
    }

    @Test
    void 개선_답변_응답_JSON_키는_camelCase_다() throws Exception {
        givenReport(finished(ReportStatus.PARTIAL, CONTRACT_RESULT));

        JsonNode json = objectMapper.valueToTree(service.getImprovements(USER_ID, PUBLIC_ID.toString()));

        JsonNode answer = json.path("improvedAnswers").get(0);
        assertThat(answer.has("isReask")).isTrue();
        assertThat(answer.has("originalQuestionId")).isTrue();
        assertThat(answer.has("tStart")).isTrue();
        assertThat(fieldNames(json)).noneMatch(name -> name.contains("_"));
    }

    @Test
    void 개선_답변도_분석_중이면_REPORT_NOT_READY_실패면_REPORT_FAILED() {
        givenReport(report(ReportStatus.PROCESSING, null, null));
        assertErrorCode(() -> service.getImprovements(USER_ID, PUBLIC_ID.toString()), ErrorCode.REPORT_NOT_READY);

        givenReport(report(ReportStatus.FAILED, null, COMPLETED_AT));
        assertErrorCode(() -> service.getImprovements(USER_ID, PUBLIC_ID.toString()), ErrorCode.REPORT_FAILED);
    }

    /** improved_answers 만 바꾼 결과. 나머지 칸은 저장 전 검증을 통과하는 최소한만 둡니다. */
    private static String improvements(String items) {
        return """
                { "report_status": "complete", "overall": { "score": 70 },
                  "axes": { "content": { "status": "ok" }, "speech": { "status": "ok" }, "gaze": { "status": "ok" } },
                  "questions": [], "improved_answers": [ %s ] }
                """.formatted(items);
    }

    private void assertErrorCode(ErrorCode expected) {
        assertErrorCode(() -> service.getDetail(USER_ID, PUBLIC_ID.toString()), expected);
    }

    private void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    /** REASK_RESULT 의 질문들. q_1r 은 q_1 의 되묻기라 문항 번호가 같습니다. */
    private void givenQuestionsInDb() {
        when(sessionQueryService.findQuestionsInOrderForInternal(SESSION_ID)).thenReturn(List.of(
                question("q_1", QuestionType.QUESTION, 1, null, "지원 동기를 말씀해 주세요"),
                question("q_1r", QuestionType.REASK, 1, "q_1", "조금 더 구체적으로 말씀해 주시겠어요?"),
                question("q_2", QuestionType.QUESTION, 2, null, "가장 어려웠던 협업 경험은 무엇인가요?"),
                question("q_3", QuestionType.FOLLOWUP, 3, null, "그때 본인의 역할은 무엇이었나요?")));
    }

    private static Question question(String id, QuestionType type, int number, String reaskOf, String text) {
        return Question.builder().sessionId(SESSION_ID).questionId(id).type(type)
                .questionNumber(number).reaskOf(reaskOf).text(text).build();
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
