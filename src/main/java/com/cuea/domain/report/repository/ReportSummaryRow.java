package com.cuea.domain.report.repository;

import com.cuea.domain.report.entity.ReportStatus;

import java.util.UUID;

/** 리포트 요약 조회 결과. 결과 원본(report_data)을 읽지 않으려고 필요한 칸만 꺼냅니다. */
public record ReportSummaryRow(String sessionId, UUID publicId, ReportStatus status, Integer scoreTotal) {
}
