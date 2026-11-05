package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.report.dto.response.ReportDetailResponse;
import com.cuea.domain.report.dto.response.ReportImprovementsResponse;
import com.cuea.domain.report.dto.response.ReportQuestions;
import com.cuea.domain.report.dto.response.ReportRetryInfo;
import com.cuea.domain.report.dto.response.ReportTimelineResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.infrastructure.ai.AiReportResultReader;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 끝난 리포트의 조회. <b>본인 리포트만 보입니다.</b>
 *
 * <p>끝난 리포트(COMPLETED · PARTIAL)만 내려줍니다. 진행 중이면 202 {@code REPORT_NOT_READY},
 * 실패했으면 409 {@code REPORT_FAILED} 입니다. 프론트는 상태 조회나 WebSocket 으로 끝난 걸
 * 확인한 뒤 부르므로, 보통은 여기서 걸리지 않습니다.
 *
 * <p>리포트와 질문을 한 번에 읽으므로 읽기 전용 트랜잭션으로 묶습니다. 외부 호출이 없어
 * 커넥션을 오래 붙들지 않습니다.
 */
@Service
@RequiredArgsConstructor
public class ReportQueryService {

    private final ReportFinder reportFinder;
    private final InterviewSessionQueryService sessionQueryService;
    private final AiReportResultReader resultReader;

    @Transactional(readOnly = true)
    public ReportDetailResponse getDetail(String userId, String reportId) {
        Report report = reportFinder.getOwned(userId, reportId);
        AiReportResult result = readFinished(report);
        return ReportDetailResponse.of(report, result, questions(report), retryInfo(report, result));
    }

    @Transactional(readOnly = true)
    public ReportTimelineResponse getTimeline(String userId, String reportId) {
        Report report = reportFinder.getOwned(userId, reportId);
        AiReportResult result = readFinished(report);
        return ReportTimelineResponse.of(report, result, questions(report), retryInfo(report, result));
    }

    @Transactional(readOnly = true)
    public ReportImprovementsResponse getImprovements(String userId, String reportId) {
        Report report = reportFinder.getOwned(userId, reportId);
        return ReportImprovementsResponse.of(report, readFinished(report), questions(report));
    }

    /** 끝난 리포트의 AI 결과. 진행 중 · 실패한 리포트는 여기서 막습니다. */
    private AiReportResult readFinished(Report report) {
        // switch 식이라 ReportStatus 가 늘면 여기서 컴파일이 깨집니다. 새 상태를 빠뜨리지 않게 하려는 것입니다.
        return switch (report.getStatus()) {
            case PROCESSING -> throw new BusinessException(ErrorCode.REPORT_NOT_READY);
            case FAILED -> throw new BusinessException(ErrorCode.REPORT_FAILED);
            case COMPLETED, PARTIAL -> readResult(report);
        };
    }

    /** 재시도 축은 이 리포트의 실패한 축({@code overall.axes_failed}) 그대로입니다. */
    private ReportRetryInfo retryInfo(Report report, AiReportResult result) {
        return ReportRetryInfos.of(report, result.failedAxes());
    }

    /** 저장 전에 모양을 확인하므로 정상이라면 실패하지 않습니다. 실패하면 500 입니다. */
    private AiReportResult readResult(Report report) {
        if (report.getReportData() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "끝난 리포트에 결과가 없습니다");
        }
        return resultReader.read(report.getReportData());
    }

    /** 되묻기 포함. 다른 도메인이라 면접 서비스로 읽고, 소유권은 리포트를 찾을 때 확인했습니다. */
    private ReportQuestions questions(Report report) {
        String sessionId = report.getSession().getSessionId();
        return ReportQuestions.of(sessionQueryService.findQuestionsInOrderForInternal(sessionId));
    }
}
