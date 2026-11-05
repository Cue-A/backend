package com.cuea.domain.report.service;

import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;

import java.util.Map;

/**
 * 끝난(COMPLETED · PARTIAL) 리포트와 그 원본. 다른 도메인에 엔티티 대신 넘깁니다.
 *
 * @param reportId   외부 식별자({@code public_id})
 * @param reportData AI 결과 원본. 키는 snake_case 그대로입니다
 */
public record FinishedReport(String reportId, String sessionId, ReportStatus status, Map<String, Object> reportData) {

    static FinishedReport of(Report report) {
        return new FinishedReport(report.getPublicId().toString(), report.getSession().getSessionId(),
                report.getStatus(), ReportFinder.reportDataOf(report));
    }
}
