package com.cuea.domain.report.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** {@link ReportProgressStore} 를 메모리로 흉내 냅니다. TTL 은 보지 않습니다. */
class FakeReportProgressStore implements ReportProgressStore {

    private final Map<String, ReportProgress> progressByReport = new HashMap<>();

    @Override
    public void save(String reportId, ReportProgress progress, Duration ttl) {
        progressByReport.put(reportId, progress);
    }

    @Override
    public Optional<ReportProgress> find(String reportId) {
        return Optional.ofNullable(progressByReport.get(reportId));
    }

    @Override
    public void delete(String reportId) {
        progressByReport.remove(reportId);
    }
}
