package com.cuea.domain.report.service;

import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 다른 도메인이 끝난 리포트의 원본을 읽어갈 때의 창구입니다. 회차 비교가 씁니다. 본인 리포트만 나옵니다.
 */
@Service
@RequiredArgsConstructor
public class FinishedReportQueryService {

    private static final List<ReportStatus> FINISHED = List.of(ReportStatus.COMPLETED, ReportStatus.PARTIAL);

    private final ReportFinder reportFinder;
    private final ReportRepository reportRepository;

    /** 끝난 본인 리포트. 상세 조회와 같은 규칙(202 · 409 · 404)입니다. */
    @Transactional(readOnly = true)
    public FinishedReport getFinished(String userId, String reportId) {
        return FinishedReport.of(reportFinder.getFinished(userId, reportId));
    }

    /** 세션 ID → 끝난 리포트. 리포트가 없거나 아직 분석 중 · 실패인 세션은 빠집니다. */
    @Transactional(readOnly = true)
    public Map<String, FinishedReport> findFinishedBySessionIds(String userId, Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return reportRepository.findAllBySessionIds(userId, sessionIds, FINISHED).stream()
                .map(FinishedReport::of)
                .collect(Collectors.toMap(FinishedReport::sessionId, report -> report));
    }
}
