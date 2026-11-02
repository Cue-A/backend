package com.cuea.domain.home.dto.response;

import com.cuea.domain.report.entity.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 홈 대시보드 "최근 리포트 요약" 카드 한 장.
 *
 * <p>프론트 목업({@code frontend} {@code src/domain/home})의 {@code title}·{@code kind}·
 * {@code durationSec} 는 저장된 값이 없어 담지 않습니다. 면접 제목은 프론트가
 * {@code companyName} 과 {@code jobRole} 로 조합합니다(회사 미선택이면 {@code companyName} 이
 * null 이라 직무만 씁니다).
 *
 * @param reportId      외부 식별자. {@code GET /api/reports/{reportId}} 상세로 바로 이어집니다
 * @param companyName   회사를 고르지 않은 연습이면 null ({@code session.company} 가 nullable)
 * @param jobRole       지원 직무
 * @param questionCount 고른 문항 수(되묻기는 세지 않음)
 * @param status        COMPLETED | PARTIAL. 홈에는 이 둘(열람 가능한 리포트)만 노출합니다
 * @param scoreTotal    종합 점수 0~100. PARTIAL 등으로 점수가 없으면 null (임의로 0 을 만들지 않음)
 * @param completedAt   리포트가 완료(열람 가능)된 시각. 최근 리포트 정렬 기준이기도 합니다
 */
@Schema(description = "홈 최근 리포트 요약 한 건")
public record RecentReportResponse(

        String reportId,

        @Schema(description = "회사를 고르지 않았으면 null")
        String companyName,

        String jobRole,

        @Schema(description = "고른 문항 수. 되묻기는 세지 않습니다")
        int questionCount,

        @Schema(description = "COMPLETED | PARTIAL")
        ReportStatus status,

        @Schema(description = "종합 점수 0~100. 점수가 없으면 null")
        Integer scoreTotal,

        @Schema(description = "리포트가 완료(열람 가능)된 시각. 정렬 기준입니다")
        OffsetDateTime completedAt
) {
}
