package com.cuea.domain.report.repository;

import com.cuea.domain.report.entity.ReportStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 홈 "최근 리포트 요약" 조회 결과 한 줄.
 *
 * <p>{@link ReportSummaryRow} 와 같은 이유로, 필요한 칸만 꺼내 {@code report_data}(jsonb)
 * 전체를 읽지 않습니다. 회사명은 세션의 회사에서 오며 회사 미선택 연습이면 null 입니다.
 *
 * @param publicId      외부 식별자(응답의 {@code reportId})
 * @param companyName   회사 미선택이면 null
 * @param jobRole       지원 직무
 * @param questionCount 고른 문항 수
 * @param status        리포트 상태(COMPLETED · PARTIAL 만 조회 대상)
 * @param scoreTotal    종합 점수. 없으면 null
 * @param practicedOn   연습(세션)을 완료한 시각. 재분석에도 바뀌지 않으며 정렬 기준입니다
 */
public record RecentReportRow(
        UUID publicId,
        String companyName,
        String jobRole,
        int questionCount,
        ReportStatus status,
        Integer scoreTotal,
        OffsetDateTime practicedOn
) {
}
