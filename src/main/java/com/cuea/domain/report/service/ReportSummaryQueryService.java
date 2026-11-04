package com.cuea.domain.report.service;

import com.cuea.domain.report.dto.response.ReportSummary;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.RecentReportRow;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.domain.report.repository.ReportSummaryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 다른 도메인이 세션별 리포트 요약을 읽어갈 때의 창구입니다. 본인 세션의 리포트만 나옵니다. */
@Service
@RequiredArgsConstructor
public class ReportSummaryQueryService {

    /** 홈 최근 리포트에 노출하는 상태. 클릭하면 바로 상세로 이어지는 열람 가능한 둘입니다. */
    private static final List<ReportStatus> VIEWABLE = List.of(ReportStatus.COMPLETED, ReportStatus.PARTIAL);

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

    /**
     * 홈 "최근 리포트 요약"용. 본인의 열람 가능한(COMPLETED · PARTIAL) 리포트를 연습을 최근에 본
     * 순서로 {@code limit} 건 돌려줍니다(Issue #107).
     *
     * <p>home 도메인이 {@link ReportRepository} 를 직접 쓰지 않도록 이 창구를 거칩니다
     * (도메인 간 참조는 service 레벨에서만 — docs/01-conventions.md). 상태 필터는 리포트 도메인의
     * 규칙이라 여기서 정하고, home 은 건수만 넘깁니다.
     */
    @Transactional(readOnly = true)
    public List<RecentReportRow> findRecentViewable(String userId, int limit) {
        Pageable top = PageRequest.of(0, limit);
        return reportRepository.findRecentViewable(userId, VIEWABLE, top);
    }

    private static ReportSummary summaryOf(ReportSummaryRow row) {
        return new ReportSummary(row.publicId().toString(), row.status(), row.scoreTotal());
    }
}
