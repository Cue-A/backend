package com.cuea.infrastructure.ai.dto;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 리포트 결과({@code report.report_data}) 전체를 읽기 위한 모양. 리포트 계약 13장.
 *
 * <p>저장은 여전히 원본 그대로 합니다(회차 비교 때 AI 에 다시 보냄). 이 레코드는 상세
 * 조회에서 원본을 camelCase 로 옮겨 내보낼 때만 씁니다. 모르는 키는 무시하므로 AI 가
 * 필드를 늘려도 깨지지 않습니다.
 *
 * <p>{@code status} · {@code kind} · {@code category} 같은 AI 값은 enum 으로 바꾸지 않고
 * 문자열 그대로 둡니다. CLAUDE.md 참고.
 *
 * @param reportStatus complete | partial
 */
@AiJson
public record AiReportResult(
        String sessionId,
        OffsetDateTime generatedAt,
        String reportStatus,
        Overall overall,
        Axes axes,
        List<Question> questions,
        Resilience resilience,
        String companyComment,
        List<ImprovedAnswer> improvedAnswers
) {

    /**
     * @param score      0~100. 게이트가 적용된 최종값
     * @param display    1~5
     * @param gateReason 게이트가 걸렸을 때만. 지금은 content_relevance_low 하나
     */
    @AiJson
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

    @AiJson
    public record Axes(Axis content, Axis speech, Axis gaze) {
    }

    /**
     * @param status    ok | failed | skipped
     * @param errorCode failed 일 때만 (SPEECH_FAILED · GAZE_FAILED)
     * @param reason    skipped 일 때만 (no_video)
     * @param metrics   축별 지표. 세부 필드가 미확정이라 해석하지 않습니다. 실패·미사용이면 null
     */
    @AiJson
    public record Axis(
            String status,
            String errorCode,
            String reason,
            Integer score,
            Integer display,
            Map<String, Object> metrics,
            List<Evidence> evidence
    ) {

        /**
         * 지표 키를 camelCase 로 바꿉니다. 값은 건드리지 않습니다.
         *
         * <p>키가 정해지지 않아 레코드로 못 옮기지만, 프론트에 snake_case 를 내보내지 않으려고
         * 키 이름만 바꿉니다. {@code hesitation_score} → {@code hesitationScore}.
         */
        public Map<String, Object> camelCaseMetrics() {
            if (metrics == null) {
                return null;
            }
            Map<String, Object> converted = new LinkedHashMap<>();
            metrics.forEach((key, value) -> converted.put(toCamelCase(key), value));
            return converted;
        }

        private static String toCamelCase(String snake) {
            StringBuilder out = new StringBuilder(snake.length());
            boolean upperNext = false;
            for (char c : snake.toCharArray()) {
                if (c == '_') {
                    upperNext = out.length() > 0;
                } else if (upperNext) {
                    out.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    /**
     * @param tStart 그 답변 오디오 기준 초
     * @param kind   strength | weakness
     */
    @AiJson
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
     * 문항별 결과. 되묻기는 따로 나오지 않고 원 질문에 합쳐져 {@code hadReask} 가 true 입니다.
     */
    @AiJson
    public record Question(
            String questionId,
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

    /** 문항의 축별 점수. 실패·미사용 축은 null. */
    @AiJson
    public record AxisScores(Integer content, Integer speech, Integer gaze) {
    }

    @AiJson
    public record Resilience(Integer score, Integer display, String comment) {
    }

    @AiJson
    public record ImprovedAnswer(
            String questionId,
            String originalExcerpt,
            String suggestion,
            Double tStart,
            Double tEnd
    ) {
    }
}
