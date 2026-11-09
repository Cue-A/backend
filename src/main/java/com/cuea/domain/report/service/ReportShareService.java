package com.cuea.domain.report.service;

import com.cuea.domain.report.dto.response.ReportShareInfo;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 리포트 공유 링크 발급 · 해제. <b>본인 리포트만</b> 다룹니다. 공개 조회는 {@link ReportQueryService#getShared} 입니다.
 *
 * <p>링크는 {@code reportId} 가 아니라 따로 발급한 토큰입니다. 유효기간은 없고 해제할 때까지 열립니다.
 */
@Service
@RequiredArgsConstructor
public class ReportShareService {

    private final ReportFinder reportFinder;
    private final ReportRepository reportRepository;

    /**
     * 끝난 리포트(COMPLETED · PARTIAL)만 공유합니다. 상세 조회와 같이 진행 중이면 202 {@code REPORT_NOT_READY},
     * 실패했으면 409 {@code REPORT_FAILED} 입니다. 이미 공유 중이면 같은 링크를 돌려줍니다.
     */
    @Transactional
    public ReportShareInfo share(String userId, String reportId) {
        Report report = reportFinder.getFinished(userId, reportId);
        if (report.getShareId() != null) {
            return ReportShareInfo.of(report);
        }
        reportRepository.share(report.getReportId(), UUID.randomUUID(), OffsetDateTime.now());
        // 조건부 UPDATE 가 영속성 컨텍스트를 비워 다시 읽습니다. 다른 요청이 먼저 썼으면 그 링크입니다.
        return ReportShareInfo.of(reportFinder.getOwned(userId, reportId));
    }

    /** 공유하지 않은 리포트여도 오류가 아닙니다. 다시 공유하면 새 링크가 나옵니다. */
    @Transactional
    public void unshare(String userId, String reportId) {
        reportFinder.getOwned(userId, reportId).unshare();
    }
}
