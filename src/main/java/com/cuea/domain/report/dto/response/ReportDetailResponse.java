package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 리포트 상세. COMPLETED · PARTIAL 리포트만 내려갑니다.
 *
 * <p>AI 결과 원본({@code report_data})을 camelCase 로 옮긴 것입니다. 모양은 리포트 계약
 * 13장을 그대로 따르고, 문항에는 AI 결과에 없는 질문 원문({@code questionText})을 붙입니다.
 *
 * <p>AI 가 준 문자열 값({@code status} · {@code kind} · {@code category} 등)은 enum 으로
 * 바꾸지 않고 그대로 내려줍니다. 목록에 없는 값이 와도 응답이 깨지지 않게 하려는 것입니다.
 */
@Schema(description = "리포트 상세")
public record ReportDetailResponse(

        String reportId,

        String sessionId,

        @Schema(description = "COMPLETED | PARTIAL")
        ReportStatus status,

        Overall overall,

        Axes axes,

        @Schema(description = "문항별 결과. 되묻기는 원 질문에 합쳐지고 hadReask 가 true 입니다")
        List<QuestionResult> questions,

        @Schema(description = "회복력. 친절형이거나 꼬리질문이 없으면 null")
        Resilience resilience,

        @Schema(description = "인재상 기준 코멘트. 기업을 고르지 않았거나 생성에 실패하면 null")
        String companyComment,

        @Schema(description = "내용 점수가 낮은 문항 최대 2개. 없으면 빈 배열")
        List<ImprovedAnswer> improvedAnswers,

        @Schema(description = "실패 축 재시도 상태. 재시도한 적이 없거나 성공했으면 null. 재시도 중에도 리포트는 그대로 내려갑니다")
        ReportRetryInfo retry,

        @Schema(description = "AI 가 리포트를 만든 시각")
        OffsetDateTime generatedAt,

        @Schema(description = "최초 분석 요청 시각")
        OffsetDateTime createdAt,

        @Schema(description = "분석 완료 시각")
        OffsetDateTime completedAt
) {

    /**
     * @param score      총점 0~100. 게이트가 적용된 최종값
     * @param display    1~5
     * @param gated      내용 점수가 낮아 총점 상한이 걸렸는지. true 면 "내용 때문에 점수가 제한됨" 안내
     * @param gateReason gated 일 때만. 지금은 content_relevance_low
     * @param partial    말하기·시선 축 중 실패한 것이 있는지. 카메라 미사용은 실패가 아닙니다
     */
    public record Overall(
            Integer score,
            Integer display,
            boolean gated,
            String gateReason,
            boolean partial,
            List<String> axesUsed,
            List<String> axesFailed
    ) {
    }

    public record Axes(Axis content, Axis speech, Axis gaze) {
    }

    /**
     * @param status    ok | failed | skipped. skipped 는 카메라 미사용이라 실패가 아닙니다
     * @param errorCode failed 일 때만 (SPEECH_FAILED · GAZE_FAILED)
     * @param reason    skipped 일 때만 (no_video)
     * @param score     0~100. failed · skipped 면 null
     * @param display   1~5. failed · skipped 면 null
     * @param metrics   축별 지표. 키가 미확정이라 원본의 키 이름만 camelCase 로 바꿉니다.
     *                  speech 는 hesitationScore · speechRateCv · repetitionCount,
     *                  content · gaze 는 지금 빈 객체. failed · skipped 면 null
     */
    public record Axis(
            String status,
            String errorCode,
            String reason,
            Integer score,
            Integer display,
            Map<String, Object> metrics,
            List<Evidence> evidence
    ) {
    }

    /**
     * @param tStart 그 답변 오디오 기준 초
     * @param kind   strength | weakness
     * @param label  배지용 짧은 이름
     */
    public record Evidence(
            String questionId,
            Double tStart,
            Double tEnd,
            String kind,
            String label,
            String comment
    ) {
    }

    /**
     * @param questionText 질문 원문. AI 결과에 없어 Backend 가 붙입니다. 찾지 못하면 null
     * @param axes         축별 점수. 실패·미사용 축은 null
     * @param wasTimeout   true 면 감점 없이 "시간 초과로 중단됨" 으로 표시
     */
    public record QuestionResult(
            String questionId,
            String questionText,
            int questionNumber,
            String category,
            String difficulty,
            boolean isReplay,
            boolean isSpareTopic,
            Integer score,
            Integer display,
            AxisScores axes,
            String transcript,
            Double durationSec,
            Integer wordCount,
            boolean wasTimeout,
            boolean hadReask
    ) {
    }

    public record AxisScores(Integer content, Integer speech, Integer gaze) {
    }

    public record Resilience(Integer score, Integer display, String comment) {
    }

    /**
     * @param originalExcerpt 전사에 실제로 있는 구절
     */
    public record ImprovedAnswer(
            String questionId,
            String originalExcerpt,
            String suggestion,
            Double tStart,
            Double tEnd
    ) {
    }

    /**
     * @param questionTexts 질문 ID → 질문 원문
     * @param retry         재시도 상태. 재시도한 적이 없거나 성공했으면 null
     */
    public static ReportDetailResponse of(Report report, AiReportResult result,
                                          Map<String, String> questionTexts, ReportRetryInfo retry) {
        return new ReportDetailResponse(
                report.getPublicId().toString(),
                report.getSession().getSessionId(),
                report.getStatus(),
                overall(result.overall()),
                axes(result.axes()),
                map(result.questions(), q -> question(q, questionTexts.get(q.questionId()))),
                resilience(result.resilience()),
                result.companyComment(),
                map(result.improvedAnswers(), a -> new ImprovedAnswer(
                        a.questionId(), a.originalExcerpt(), a.suggestion(), a.tStart(), a.tEnd())),
                retry,
                result.generatedAt(),
                report.getCreatedAt(),
                report.getCompletedAt());
    }

    private static Overall overall(AiReportResult.Overall o) {
        if (o == null) {
            return null;
        }
        return new Overall(o.score(), o.display(), o.gated(), o.gateReason(), o.partial(),
                listOrEmpty(o.axesUsed()), listOrEmpty(o.axesFailed()));
    }

    private static Axes axes(AiReportResult.Axes a) {
        if (a == null) {
            return null;
        }
        return new Axes(axis(a.content()), axis(a.speech()), axis(a.gaze()));
    }

    private static Axis axis(AiReportResult.Axis a) {
        if (a == null) {
            return null;
        }
        return new Axis(a.status(), a.errorCode(), a.reason(), a.score(), a.display(),
                a.camelCaseMetrics(),
                map(a.evidence(), e -> new Evidence(
                        e.questionId(), e.tStart(), e.tEnd(), e.kind(), e.label(), e.comment())));
    }

    private static QuestionResult question(AiReportResult.Question q, String questionText) {
        AiReportResult.AxisScores s = q.axes();
        return new QuestionResult(
                q.questionId(), questionText, q.questionNumber(), q.category(), q.difficulty(),
                q.isReplay(), q.isSpareTopic(), q.score(), q.display(),
                s == null ? new AxisScores(null, null, null)
                        : new AxisScores(s.content(), s.speech(), s.gaze()),
                q.transcript(), q.durationSec(), q.wordCount(), q.wasTimeout(), q.hadReask());
    }

    private static Resilience resilience(AiReportResult.Resilience r) {
        return r == null ? null : new Resilience(r.score(), r.display(), r.comment());
    }

    /** 글로 된 칸은 생성에 실패하면 빈 배열이 원칙이지만, 키가 빠져도 null 대신 빈 배열로 내려줍니다. */
    private static <A, B> List<B> map(List<A> source, Function<A, B> mapper) {
        return source == null ? List.of() : source.stream().map(mapper).toList();
    }

    private static <T> List<T> listOrEmpty(List<T> source) {
        return source == null ? List.of() : source;
    }
}
