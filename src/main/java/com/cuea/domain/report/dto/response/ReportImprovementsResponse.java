package com.cuea.domain.report.dto.response;

import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.report.entity.Report;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 개선 답변 예시. COMPLETED · PARTIAL 리포트만 내려갑니다.
 *
 * <p>AI 의 {@code improved_answers[]} 를 순서 그대로 옮기고, AI 결과에 없는 원 질문 ID · 문항 번호 ·
 * 질문 원문을 우리 {@code question} 테이블에서 붙입니다. 되묻기 답변에서 발췌했으면
 * {@code questionId} 가 되묻기 ID 라 원 질문은 {@code reask_of} 로 찾습니다.
 */
@Schema(description = "개선 답변 예시")
public record ReportImprovementsResponse(

        String reportId,

        @Schema(description = "내용 점수가 낮은 문항부터 최대 2개. 모두 잘 답했거나 생성에 실패하면 빈 배열")
        List<Improvement> improvedAnswers
) {

    /**
     * @param questionId         발췌한 답변의 ID. 되묻기 답변이면 되묻기 ID. tStart · tEnd 는 이 답변 오디오 기준
     * @param originalQuestionId 원 질문 ID. 되묻기가 아니면 questionId 와 같습니다
     * @param isReask            되묻기 답변에서 발췌했는지
     * @param questionNumber     원 질문의 문항 번호. 질문을 찾지 못하면 null
     * @param questionText       원 질문 원문. 질문을 찾지 못하면 null
     * @param originalExcerpt    전사에 실제로 있는 구절
     */
    public record Improvement(
            String questionId,
            String originalQuestionId,
            boolean isReask,
            Integer questionNumber,
            String questionText,
            String originalExcerpt,
            String suggestion,
            Double tStart,
            Double tEnd
    ) {
    }

    /** @param questions 질문 ID → 질문. 되묻기 포함 */
    public static ReportImprovementsResponse of(Report report, AiReportResult result,
                                                Map<String, Question> questions) {
        List<Improvement> improvements = result.improvedAnswers() == null ? List.of()
                : result.improvedAnswers().stream().map(a -> improvement(a, questions)).toList();
        return new ReportImprovementsResponse(report.getPublicId().toString(), improvements);
    }

    private static Improvement improvement(AiReportResult.ImprovedAnswer a, Map<String, Question> questions) {
        Question answered = questions.get(a.questionId());
        String originalId = answered == null ? a.questionId() : answered.originalQuestionId();
        Question original = questions.get(originalId);
        return new Improvement(
                a.questionId(), originalId, !Objects.equals(originalId, a.questionId()),
                original == null ? null : original.getQuestionNumber(),
                original == null ? null : original.getText(),
                a.originalExcerpt(), a.suggestion(), a.tStart(), a.tEnd());
    }
}
