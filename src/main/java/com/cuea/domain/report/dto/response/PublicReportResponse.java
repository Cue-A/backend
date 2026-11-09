package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 공유 링크로 연 리포트. 본인 리포트 화면(상세 · 타임라인 · 개선 답변)을 한 응답에 담습니다.
 *
 * <p><b>본인 전용 값은 넣지 않습니다.</b> {@code reportId} · {@code sessionId} 는 본인 API 를 부르는 값이고,
 * {@code retry} · {@code share} 는 본인 화면의 버튼용입니다. 각 칸의 모양과 의미는 본인 응답과 같습니다.
 */
@Schema(description = "공유 링크로 연 리포트")
public record PublicReportResponse(

        @Schema(description = "COMPLETED | PARTIAL")
        ReportStatus status,

        ReportDetailResponse.Overall overall,

        @Schema(description = "한 줄 총평. 없으면 null")
        String summary,

        ReportDetailResponse.Axes axes,

        @Schema(description = "문항별 결과. 상세 조회의 questions 와 같습니다")
        List<ReportDetailResponse.QuestionResult> questions,

        @Schema(description = "회복력. 친절형이거나 꼬리질문이 없으면 null")
        ReportDetailResponse.Resilience resilience,

        @Schema(description = "인재상 기준 코멘트. 기업을 고르지 않았거나 생성에 실패하면 null")
        String companyComment,

        @Schema(description = "XAI 타임라인. 타임라인 조회의 questions 와 같습니다")
        List<ReportTimelineResponse.TimelineQuestion> timeline,

        @Schema(description = "개선 답변 예시. 개선 답변 조회의 improvedAnswers 와 같습니다")
        List<ReportImprovementsResponse.Improvement> improvedAnswers,

        @Schema(description = "AI 가 리포트를 만든 시각")
        OffsetDateTime generatedAt,

        @Schema(description = "최초 분석 요청 시각")
        OffsetDateTime createdAt,

        @Schema(description = "분석 완료 시각")
        OffsetDateTime completedAt
) {

    /** 본인 응답 세 개에서 본인 전용 값을 빼고 합칩니다. 축 상태와 회복력은 상세에 있어 타임라인 것은 쓰지 않습니다. */
    public static PublicReportResponse of(ReportDetailResponse detail,
                                          ReportTimelineResponse timeline,
                                          ReportImprovementsResponse improvements) {
        return new PublicReportResponse(
                detail.status(),
                detail.overall(),
                detail.summary(),
                detail.axes(),
                detail.questions(),
                detail.resilience(),
                detail.companyComment(),
                timeline.questions(),
                improvements.improvedAnswers(),
                detail.generatedAt(),
                detail.createdAt(),
                detail.completedAt());
    }
}
