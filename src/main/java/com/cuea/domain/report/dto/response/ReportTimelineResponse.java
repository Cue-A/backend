package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XAI 타임라인. 축별 근거를 원 질문별로 묶습니다. 되묻기 근거(q_1r)도 원 질문 아래로 들어갑니다.
 * 시간은 그 답변 오디오 기준 초 그대로입니다.
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

    /** 원 답변 근거 → 되묻기 답변 근거, 각각 tStart 순. */
    private static final Comparator<TimelineEvidence> ANSWER_ORDER = Comparator
            .comparing(TimelineEvidence::isReask)
            .thenComparing(TimelineEvidence::tStart, Comparator.nullsLast(Comparator.naturalOrder()));

    /** AI 값 그대로. 축이 빠졌으면 null. */
    public record AxisStatuses(String content, String speech, String gaze) {
    }

    /**
     * @param questionId   원 질문 ID
     * @param questionText 찾지 못하면 null
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
     * @param questionId 근거가 나온 답변의 ID. 되묻기면 되묻기 ID
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

    public static ReportTimelineResponse of(Report report, AiReportResult result,
                                            ReportQuestions questions, ReportRetryInfo retry) {
        Map<String, List<TimelineEvidence>> evidence = evidenceByOriginalQuestion(result.axes(), questions);
        List<TimelineQuestion> timeline = result.questions() == null ? List.of()
                : result.questions().stream()
                        .map(q -> new TimelineQuestion(q.questionId(), q.questionNumber(),
                                questions.textOf(q.questionId()), q.durationSec(), q.hadReask(),
                                evidence.getOrDefault(q.questionId(), List.of())))
                        .toList();
        return new ReportTimelineResponse(
                report.getPublicId().toString(),
                report.getStatus(),
                axisStatuses(result.axes()),
                timeline,
                ReportDetailResponse.Resilience.from(result.resilience()),
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

    /** 원 질문 ID → 근거. 원 질문이 questions[] 에 없는 근거는 붙을 곳이 없어 빠집니다. */
    private static Map<String, List<TimelineEvidence>> evidenceByOriginalQuestion(
            AiReportResult.Axes axes, ReportQuestions questions) {
        Map<String, List<TimelineEvidence>> grouped = new HashMap<>();
        if (axes != null) {
            addEvidence(grouped, "content", axes.content(), questions);
            addEvidence(grouped, "speech", axes.speech(), questions);
            addEvidence(grouped, "gaze", axes.gaze(), questions);
        }
        grouped.values().forEach(list -> list.sort(ANSWER_ORDER));
        return grouped;
    }

    private static void addEvidence(Map<String, List<TimelineEvidence>> grouped, String axisName,
                                    AiReportResult.Axis axis, ReportQuestions questions) {
        if (axis == null || axis.evidence() == null) {
            return;
        }
        for (AiReportResult.Evidence e : axis.evidence()) {
            grouped.computeIfAbsent(questions.originalIdOf(e.questionId()), id -> new ArrayList<>())
                    .add(new TimelineEvidence(axisName, e.questionId(), questions.isReask(e.questionId()),
                            e.tStart(), e.tEnd(), e.kind(), e.label(), e.comment()));
        }
    }
}
