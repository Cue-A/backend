package com.cuea.domain.report.service;

import com.cuea.domain.report.dto.response.ReportSummary;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.MonthlyScoreRow;
import com.cuea.domain.report.repository.RecentReportRow;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.domain.report.repository.ReportSummaryRow;
import com.cuea.domain.report.repository.ScorePointRow;
import com.cuea.domain.report.repository.ScoreSummaryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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

    /**
     * 홈 "기간 평균·최고" 점수 창구(Issue #114). 열람 가능하고 점수가 있는 리포트만 평균·최고를 냅니다.
     * 기간은 세션 완료 시각 기준 반열림 구간 {@code [start, end)}. 대상이 없으면 두 값 모두 null.
     *
     * <p>home 도메인이 {@link ReportRepository} 를 직접 쓰지 않도록 이 창구를 거칩니다
     * (도메인 간 참조는 service 레벨에서만 — docs/01-conventions.md).
     */
    @Transactional(readOnly = true)
    public ScoreSummaryRow summarizeScores(String userId, OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
        return reportRepository.summarizeScores(userId, startInclusive, endExclusive);
    }

    /**
     * 홈 점수 추이(회차별) 창구(Issue #114). 점수 있는 리포트를 세션 완료 최신순으로 {@code limit} 개.
     * 호출부(home)가 오래된 순으로 뒤집어 그래프를 그립니다.
     */
    @Transactional(readOnly = true)
    public List<ScorePointRow> findScorePointsLatestFirst(String userId, OffsetDateTime startInclusive,
                                                          OffsetDateTime endExclusive, int limit) {
        Pageable top = PageRequest.of(0, limit);
        return reportRepository.findScorePointsLatestFirst(userId, startInclusive, endExclusive, top);
    }

    /**
     * 홈 점수 추이(월 평균) 창구(Issue #114). KST 월별 평균을 오래된 달부터. 점수 없는 달은 행이 없습니다.
     */
    @Transactional(readOnly = true)
    public List<MonthlyScoreRow> findMonthlyScoreAverages(String userId, OffsetDateTime startInclusive,
                                                          OffsetDateTime endExclusive) {
        return reportRepository.findMonthlyScoreAverages(userId, startInclusive, endExclusive);
    }

    private static ReportSummary summaryOf(ReportSummaryRow row) {
        return new ReportSummary(row.publicId().toString(), row.status(), row.scoreTotal());
    }
}
