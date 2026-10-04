package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.report.dto.response.ReportRetryResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportRetryStatus;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.AiReportResultReader;
import com.cuea.infrastructure.ai.dto.AiReportRetryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PARTIAL 리포트의 실패한 축만 다시 분석합니다. AI 에 작업을 맡기고 즉시 반환합니다.
 *
 * <p>폴링 · 결과 저장 · WebSocket 통지는 생성과 같은 {@link ReportPoller} 가 합니다. 재시도
 * 중에도 리포트는 PARTIAL 로 계속 조회되고, {@code retry_status} 만 PROCESSING 이 됩니다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않습니다.</b> {@link ReportRequestService} 와 같은
 * 이유로 AI 호출을 트랜잭션 밖에 두고, AI 가 받아준 뒤에만 {@link ReportWriter} 로 상태를
 * 바꿉니다. AI 가 거절하면 리포트를 건드리지 않습니다. 동시 재시도는 같은 Idempotency-Key 로
 * AI 를 부르므로 AI 작업은 하나고, 조건부 UPDATE 에서 한쪽만 통과합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportRetryService {

    private final ReportFinder reportFinder;
    private final ReportRequestAssembler requestAssembler;
    private final ReportWriter reportWriter;
    private final AiReportResultReader resultReader;
    private final AiClient aiClient;

    public ReportRetryResponse retry(String userId, String reportId) {
        Report report = reportFinder.getOwned(userId, reportId);
        if (report.getStatus() != ReportStatus.PARTIAL) {
            throw new BusinessException(ErrorCode.REPORT_NOT_RETRYABLE);
        }
        if (report.getRetryStatus() == ReportRetryStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.REPORT_RETRY_IN_PROGRESS);
        }
        List<String> axes = failedAxes(report);

        String sessionId = report.getSession().getSessionId();
        int attempt = report.getAttempt() + 1;
        AiReportRetryRequest body = AiReportRetryRequest.of(axes, requestAssembler.build(sessionId));
        String taskId = aiClient.retryReport(sessionId, ReportRequestService.idempotencyKey(sessionId, attempt), body);
        log.info("리포트 재시도 등록 sessionId={} attempt={} axes={} taskId={}", sessionId, attempt, axes, taskId);

        Report started = reportWriter.startRetry(report, sessionId, taskId, axes);
        return new ReportRetryResponse(started.getPublicId().toString(), started.getStatus(),
                ReportRetryInfos.of(started, axes));
    }

    /**
     * 부분 리포트의 {@code overall.axes_failed} 그대로. 비어 있으면 AI 가 400 을 주므로 미리 막습니다.
     * PARTIAL 이면 정상적으로는 비지 않습니다.
     */
    private List<String> failedAxes(Report report) {
        if (report.getReportData() == null) {
            throw new BusinessException(ErrorCode.REPORT_NOT_RETRYABLE);
        }
        List<String> axes = resultReader.read(report.getReportData()).failedAxes();
        if (axes.isEmpty()) {
            throw new BusinessException(ErrorCode.REPORT_NOT_RETRYABLE);
        }
        return List.copyOf(axes);
    }
}
