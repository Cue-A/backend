package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 리포트 DB 쓰기만 담당합니다.
 *
 * <p>AI 호출을 트랜잭션 밖에 두려고 서비스에서 떼어냈습니다. 여기 메서드는 전부 짧은
 * 트랜잭션이며 외부 호출을 하지 않습니다. docs/01-conventions.md 의 트랜잭션 항목 참고.
 */
@Component
@RequiredArgsConstructor
public class ReportWriter {

    private static final TypeReference<Map<String, Object>> RAW_TYPE = new TypeReference<>() {
    };

    private final ReportRepository reportRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 첫 요청의 리포트 행을 만들고, 커밋되면 폴링을 시작하도록 이벤트를 냅니다.
     *
     * <p>같은 세션으로 동시에 들어온 요청은 {@code session_id} UNIQUE 에 걸려
     * {@code DataIntegrityViolationException} 이 납니다. 호출하는 쪽이 409 로 바꿉니다.
     */
    @Transactional
    public Report createProcessing(InterviewSession session, String aiTaskId) {
        Report report = reportRepository.save(Report.processing(session, aiTaskId));
        publishRequested(report, session.getSessionId());
        return report;
    }

    /**
     * 실패한 리포트를 새 시도로 되돌립니다.
     *
     * @throws BusinessException 다른 요청이 먼저 되돌렸으면 {@code REPORT_ALREADY_EXISTS}
     */
    @Transactional
    public Report reopenFailed(Report failed, String sessionId, String aiTaskId) {
        int nextAttempt = failed.getAttempt() + 1;
        int updated = reportRepository.reopenFailed(
                failed.getReportId(), failed.getAttempt(), nextAttempt, aiTaskId,
                ReportStatus.PROCESSING, ReportStatus.FAILED);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_EXISTS);
        }
        Report reopened = findForInternal(failed.getReportId());
        publishRequested(reopened, sessionId);
        return reopened;
    }

    /** 백그라운드 자동 재시도로 새 task 를 받았습니다. */
    @Transactional
    public void recordRetry(Long reportId, int attempt, String aiTaskId) {
        findForInternal(reportId).retryWith(attempt, aiTaskId);
    }

    @Transactional
    public Report finish(Long reportId, ReportResult result) {
        Report report = findForInternal(reportId);
        report.finish(
                result.status(),
                result.scoreTotal(),
                result.scoreContent(),
                result.scoreSpeech(),
                result.scoreGaze(),
                objectMapper.convertValue(result.raw(), RAW_TYPE));
        return report;
    }

    @Transactional
    public void fail(Long reportId, ErrorCode errorCode) {
        findForInternal(reportId).fail(errorCode.name());
    }

    private Report findForInternal(Long reportId) {
        return reportRepository.findByIdForInternal(reportId)
                .orElseThrow(() -> new IllegalStateException("리포트 행이 없습니다 reportId=" + reportId));
    }

    private void publishRequested(Report report, String sessionId) {
        eventPublisher.publishEvent(new ReportRequestedEvent(
                report.getReportId(), report.getPublicId(), sessionId,
                report.getAiTaskId(), report.getAttempt()));
    }
}
