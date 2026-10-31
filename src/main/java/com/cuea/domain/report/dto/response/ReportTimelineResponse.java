package com.cuea.domain.report.dto.response;

import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * XAI 타임라인. 축별 근거를 문항별로 묶고 회복력을 붙입니다. COMPLETED · PARTIAL 리포트만 내려갑니다.
 *
 * <p>AI 는 근거를 축별로 주고, 되묻기 답변에서 나온 근거는 {@code questionId} 가 되묻기 ID
 * ({@code q_1r})입니다. 반면 문항({@code questions[]})은 되묻기를 원 질문에 합쳐 내보내므로 그대로는
 * 이어지지 않습니다. 원 질문은 우리 {@code question.reask_of} 에만 있어 여기서 원 질문 아래로 모읍니다.
 *
 * <p>시간({@code tStart} · {@code tEnd})은 그 답변 오디오 기준 초 그대로입니다. 한 줄 타임라인의
 * 위치는 프론트가 {@code durationSec} 를 이어 붙여 계산합니다.
 */
@Schema(description = "XAI 타임라인 · 회복력")
public record ReportTimelineResponse(

        String reportId,

        @Schema(description = "COMPLETED | PARTIAL")
        ReportStatus status,

        @Schema(description = "축 상태 ok | failed | skipped. 근거가 빈 이유를 구분할 때 씁니다")
        AxisStatuses axes,

        @Schema(description = "문항 순서대로. 근거가 없는 문항도 들어갑니다")
        List<TimelineQuestion> questions,

        @Schema(description = "회복력. 친절형이거나 꼬리질문이 없으면 null")
        ReportDetailResponse.Resilience resilience,

        @Schema(description = "실패 축 재시도 상태. 재시도한 적이 없거나 성공했으면 null")
        ReportRetryInfo retry
) {

    /** 원 답변 근거가 먼저, 되묻기 답변 근거가 뒤. 각각 시작 시각 순입니다. */
    private static final Comparator<TimelineEvidence> ANSWER_ORDER = Comparator
            .comparing(TimelineEvidence::isReask)
            .thenComparing(TimelineEvidence::tStart, Comparator.nullsLast(Comparator.naturalOrder()));

    /** AI 값 그대로. 축이 빠졌으면 null. */
    public record AxisStatuses(String content, String speech, String gaze) {
    }

    /**
     * @param questionId   원 질문 ID. 상세 조회 {@code questions[]} 와 같은 기준
     * @param questionText 질문 원문. 찾지 못하면 null
     * @param durationSec  문항 답변 길이(초). 문항 구간을 이어 붙일 때 씁니다
     * @param evidence     원 답변 근거 → 되묻기 답변 근거 순, 각각 tStart 오름차순
     */
    public record TimelineQuestion(
            String questionId,
            int questionNumber,
            String questionText,
            Double durationSec,
            boolean hadReask,
            List<TimelineEvidence> evidence
    ) {
    }

    /**
     * @param axis       content | speech | gaze
     * @param questionId 근거가 나온 답변의 ID. 되묻기 답변이면 되묻기 ID
     * @param isReask    되묻기 답변에서 나온 근거인지
     * @param tStart     그 답변 오디오 기준 초
     * @param kind       strength | weakness
     */
    public record TimelineEvidence(
            String axis,
            String questionId,
            boolean isReask,
            Double tStart,
            Double tEnd,
            String kind,
            String label,
            String comment
    ) {
    }

    /**
     * @param questions 질문 ID → 질문. 되묻기 포함
     * @param retry     재시도 상태. 재시도한 적이 없거나 성공했으면 null
     */
    public static ReportTimelineResponse of(Report report, AiReportResult result,
                                            Map<String, Question> questions, ReportRetryInfo retry) {
        Map<String, List<TimelineEvidence>> evidence = evidenceByOriginalQuestion(result.axes(), questions);
        List<TimelineQuestion> timeline = result.questions() == null ? List.of()
                : result.questions().stream()
                        .map(q -> new TimelineQuestion(q.questionId(), q.questionNumber(),
                                textOf(questions.get(q.questionId())), q.durationSec(), q.hadReask(),
                                evidence.getOrDefault(q.questionId(), List.of())))
                        .toList();
        AiReportResult.Resilience r = result.resilience();
        return new ReportTimelineResponse(
                report.getPublicId().toString(),
                report.getStatus(),
                axisStatuses(result.axes()),
                timeline,
                r == null ? null : new ReportDetailResponse.Resilience(r.score(), r.display(), r.comment()),
                retry);
    }

    private static AxisStatuses axisStatuses(AiReportResult.Axes a) {
        if (a == null) {
            return new AxisStatuses(null, null, null);
        }
        return new AxisStatuses(statusOf(a.content()), statusOf(a.speech()), statusOf(a.gaze()));
    }

    private static String statusOf(AiReportResult.Axis axis) {
        return axis == null ? null : axis.status();
    }

    /**
     * 원 질문 ID → 근거. 원 질문을 찾지 못한 근거는 어느 문항에도 붙지 않아 빠집니다.
     * AI 는 답한 질문에서만 근거를 뽑으므로 정상이라면 생기지 않습니다.
     */
    private static Map<String, List<TimelineEvidence>> evidenceByOriginalQuestion(
            AiReportResult.Axes axes, Map<String, Question> questions) {
        if (axes == null) {
            return Map.of();
        }
        Map<String, List<TimelineEvidence>> grouped = new HashMap<>();
        addEvidence(grouped, "content", axes.content(), questions);
        addEvidence(grouped, "speech", axes.speech(), questions);
        addEvidence(grouped, "gaze", axes.gaze(), questions);
        grouped.values().forEach(list -> list.sort(ANSWER_ORDER));
        return grouped;
    }

    private static void addEvidence(Map<String, List<TimelineEvidence>> grouped, String axisName,
                                    AiReportResult.Axis axis, Map<String, Question> questions) {
        if (axis == null || axis.evidence() == null) {
            return;
        }
        for (AiReportResult.Evidence e : axis.evidence()) {
            Question answered = questions.get(e.questionId());
            String originalId = answered == null ? e.questionId() : answered.originalQuestionId();
            grouped.computeIfAbsent(originalId, id -> new ArrayList<>())
                    .add(new TimelineEvidence(axisName, e.questionId(), !Objects.equals(originalId, e.questionId()),
                            e.tStart(), e.tEnd(), e.kind(), e.label(), e.comment()));
        }
    }

    private static String textOf(Question question) {
        return question == null ? null : question.getText();
    }
}
