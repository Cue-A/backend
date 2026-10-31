package com.cuea.domain.report.service;

import com.cuea.domain.report.dto.response.ReportSummary;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.domain.report.repository.ReportSummaryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/** 다른 도메인이 세션별 리포트 요약을 읽어갈 때의 창구입니다. 본인 세션의 리포트만 나옵니다. */
@Service
@RequiredArgsConstructor
public class ReportSummaryQueryService {

    private final ReportRepository reportRepository;

    /** 세션 ID → 요약. 리포트를 요청하지 않은 세션은 빠집니다. */
    @Transactional(readOnly = true)
    public Map<String, ReportSummary> findBySessionIds(String userId, Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return reportRepository.findSummaries(userId, sessionIds).stream()
                .collect(Collectors.toMap(ReportSummaryRow::sessionId, ReportSummaryQueryService::summaryOf));
    }

    private static ReportSummary summaryOf(ReportSummaryRow row) {
        return new ReportSummary(row.publicId().toString(), row.status(), row.scoreTotal());
    }
}
