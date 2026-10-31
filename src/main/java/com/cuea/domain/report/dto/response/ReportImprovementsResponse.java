package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.Report;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 개선 답변 예시. AI 순서 그대로이고, AI 결과에 없는 원 질문 ID · 번호 · 원문을 붙입니다. */
@Schema(description = "개선 답변 예시")
public record ReportImprovementsResponse(

        String reportId,

        @Schema(description = "내용 점수가 낮은 문항부터 최대 2개. 모두 잘 답했거나 생성에 실패하면 빈 배열")
        List<Improvement> improvedAnswers
) {

    /**
     * @param questionId         발췌한 답변의 ID. 되묻기면 되묻기 ID. tStart · tEnd 의 기준 오디오
     * @param originalQuestionId 되묻기가 아니면 questionId 와 같음
     * @param questionNumber     원 질문 것. 찾지 못하면 null
     * @param questionText       원 질문 것. 찾지 못하면 null
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

    public static ReportImprovementsResponse of(Report report, AiReportResult result, ReportQuestions questions) {
        List<Improvement> improvements = result.improvedAnswers() == null ? List.of()
                : result.improvedAnswers().stream().map(a -> improvement(a, questions)).toList();
        return new ReportImprovementsResponse(report.getPublicId().toString(), improvements);
    }

    private static Improvement improvement(AiReportResult.ImprovedAnswer a, ReportQuestions questions) {
        String originalId = questions.originalIdOf(a.questionId());
        return new Improvement(
                a.questionId(), originalId, questions.isReask(a.questionId()),
                questions.numberOf(originalId), questions.textOf(originalId),
                a.originalExcerpt(), a.suggestion(), a.tStart(), a.tEnd());
    }
}
