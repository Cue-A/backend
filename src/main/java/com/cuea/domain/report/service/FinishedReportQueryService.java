package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.report.entity.Report;
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

    /**
     * 끝난 본인 리포트. 상세 조회와 같은 규칙입니다. 진행 중이면 202 {@code REPORT_NOT_READY},
     * 실패했으면 409 {@code REPORT_FAILED}, 없거나 남의 것이면 404 {@code REPORT_NOT_FOUND}.
     */
    @Transactional(readOnly = true)
    public FinishedReport getFinished(String userId, String reportId) {
        Report report = reportFinder.getOwned(userId, reportId);
        // switch 식이라 ReportStatus 가 늘면 여기서 컴파일이 깨집니다. 새 상태를 빠뜨리지 않게 하려는 것입니다.
        return switch (report.getStatus()) {
            case PROCESSING -> throw new BusinessException(ErrorCode.REPORT_NOT_READY);
            case FAILED -> throw new BusinessException(ErrorCode.REPORT_FAILED);
            case COMPLETED, PARTIAL -> FinishedReport.of(report);
        };
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
