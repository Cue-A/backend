package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.report.dto.response.ReportRequestResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 리포트 분석 작업 등록. AI 에 작업을 맡기고 즉시 반환합니다.
 *
 * <p>분석은 최대 10분이 걸려 HTTP 요청 스레드에서 기다리지 않습니다. 여기서는 AI 에
 * task 를 등록하고 리포트 행을 {@code PROCESSING} 으로 저장하는 데까지만 하고,
 * 폴링·결과 저장·WebSocket 통지는 커밋 뒤 {@link ReportPoller} 가 합니다.
 *
 * <p><b>이 클래스에 {@code @Transactional} 을 붙이지 않습니다.</b> AI 호출을 트랜잭션
 * 밖에 두고, DB 쓰기는 {@link ReportWriter} 의 짧은 트랜잭션으로 넘깁니다.
 *
 * <h2>AI 호출이 저장보다 먼저인 이유</h2>
 * AI 등록이 실패하면(422 · 503 · 504) 리포트 행을 남기지 않기 위해서입니다. 대신 동시
 * 요청은 Idempotency-Key 로 막습니다. 같은 세션의 두 요청은 같은 키
 * {@code rpt_{sessionId}_{attempt}} 로 AI 를 부르므로 AI 가 같은 task_id 를 돌려주고,
 * 저장 단계에서 한쪽만 성공합니다. AI 작업이 두 번 돌지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportRequestService {

    private final InterviewSessionQueryService sessionQueryService;
    private final ReportRepository reportRepository;
    private final ReportRequestAssembler requestAssembler;
    private final ReportWriter reportWriter;
    private final AiClient aiClient;

    public ReportRequestResponse request(String userId, String sessionId) {
        InterviewSession session = sessionQueryService.getOwnedSession(sessionId, userId);
        checkReportable(session.getStatus());

        Optional<Report> existing = reportRepository.findBySession_SessionIdAndSession_User_UserId(sessionId, userId);
        int attempt = nextAttempt(existing);

        AiReportRequest body = requestAssembler.build(sessionId);
        String taskId = aiClient.requestReport(sessionId, idempotencyKey(sessionId, attempt), body);
        log.info("리포트 작업 등록 sessionId={} attempt={} taskId={}", sessionId, attempt, taskId);

        Report report = existing.isPresent()
                ? reportWriter.reopenFailed(existing.get(), sessionId, taskId)
                : createProcessing(session, taskId);
        return ReportRequestResponse.of(report, sessionId);
    }

    /** AI Idempotency-Key. 시도 번호가 같으면 AI 가 기존 task_id 를 돌려줍니다. */
    static String idempotencyKey(String sessionId, int attempt) {
        return "rpt_%s_%d".formatted(sessionId, attempt);
    }

    private void checkReportable(SessionStatus status) {
        if (status.isReportable()) {
            return;
        }
        throw new BusinessException(status == SessionStatus.ABORTED
                ? ErrorCode.SESSION_ABORTED
                : ErrorCode.SESSION_NOT_COMPLETED);
    }

    /** 없으면 1, 실패한 리포트면 다음 번호. 그 외 상태는 이미 요청된 것이라 409 입니다. */
    private int nextAttempt(Optional<Report> existing) {
        if (existing.isEmpty()) {
            return 1;
        }
        Report report = existing.get();
        if (!report.getStatus().isRequestable()) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_EXISTS);
        }
        return report.getAttempt() + 1;
    }

    private Report createProcessing(InterviewSession session, String taskId) {
        try {
            return reportWriter.createProcessing(session, taskId);
        } catch (DataIntegrityViolationException e) {
            // 같은 세션의 동시 요청이 먼저 저장했습니다. AI task 는 같은 키라 하나뿐입니다.
            log.info("동시 요청으로 리포트가 이미 만들어졌습니다 sessionId={}", session.getSessionId());
            throw new BusinessException(ErrorCode.REPORT_ALREADY_EXISTS);
        }
    }
}
