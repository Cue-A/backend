package com.cuea.domain.report.service;

import com.cuea.common.config.AsyncConfig;
import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.config.ReportProperties;
import com.cuea.domain.report.entity.Report;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.AiPoller;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.cuea.infrastructure.websocket.ReportSocketHandler;
import com.cuea.infrastructure.websocket.message.ReportErrorPushMessage;
import com.cuea.infrastructure.websocket.message.ReportProgressPushMessage;
import com.cuea.infrastructure.websocket.message.ReportProgressStage;
import com.cuea.infrastructure.websocket.message.ReportPushMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 리포트 작업을 백그라운드에서 폴링하고, 결과를 저장한 뒤 WebSocket 으로 알립니다.
 *
 * <p><b>리포트 행이 커밋된 뒤에 시작합니다</b>({@code AFTER_COMMIT}). 커밋 전에 시작하면
 * 가상 스레드가 아직 저장되지 않은 행을 찾지 못할 수 있습니다. {@code @Async} 이므로
 * 별도 빈이어야 합니다(self-invocation 은 프록시를 거치지 않음).
 *
 * <p>여기서 던진 예외는 호출자에게 가지 않습니다. 어떤 실패든 리포트를 {@code FAILED}
 * 로 정리하고 {@code error} 를 보냅니다. 새어 나가면 리포트가 {@code PROCESSING} 으로
 * 영구히 남고 재요청도 409 로 막힙니다.
 *
 * <h2>자동 재시도</h2>
 * {@code CONTENT_FAILED} · {@code MEDIA_FETCH_FAILED} 는 한 번만 다시 요청합니다.
 * <b>시도 번호를 올린 새 Idempotency-Key 를 씁니다.</b> 같은 키면 AI 가 실패한 기존
 * task_id 를 그대로 돌려줍니다. 요청 본문도 다시 조립해 presigned URL 을 새로 받습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportPoller {

    private final AiClient aiClient;
    private final AiPoller aiPoller;
    private final ReportProperties reportProperties;
    private final ReportRequestAssembler requestAssembler;
    private final ReportWriter reportWriter;
    private final ReportSocketHandler socketHandler;

    @Async(AsyncConfig.REPORT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ReportRequestedEvent event) {
        String reportId = event.reportPublicId().toString();
        String taskId = event.aiTaskId();
        int attempt = event.attempt();
        boolean retried = false;

        while (true) {
            try {
                AiReportTaskStatusResponse done = aiPoller.await(
                        taskId, reportProperties.pollTimeout(), aiClient::getReportTask,
                        status -> pushProgress(reportId, status));
                finish(event, ReportResult.from(done));
                return;
            } catch (BusinessException e) {
                if (retried || !ReportFailurePolicy.shouldAutoRetry(e.getErrorCode())) {
                    fail(event, e);
                    return;
                }
                retried = true;
                attempt++;
                log.warn("리포트 자동 재시도 sessionId={} errorCode={} attempt={}",
                        event.sessionId(), e.getErrorCode(), attempt);
                try {
                    taskId = requestAgain(event, attempt);
                } catch (BusinessException retryError) {
                    fail(event, retryError);
                    return;
                } catch (RuntimeException retryError) {
                    fail(event, unexpected(retryError));
                    return;
                }
            } catch (RuntimeException e) {
                fail(event, unexpected(e));
                return;
            }
        }
    }

    private String requestAgain(ReportRequestedEvent event, int attempt) {
        AiReportRequest body = requestAssembler.build(event.sessionId());
        String taskId = aiClient.requestReport(
                event.sessionId(), ReportRequestService.idempotencyKey(event.sessionId(), attempt), body);
        reportWriter.recordRetry(event.reportId(), attempt, taskId);
        return taskId;
    }

    private void finish(ReportRequestedEvent event, ReportResult result) {
        Report report = reportWriter.finish(event.reportId(), result);
        log.info("리포트 생성 완료 sessionId={} status={}", event.sessionId(), report.getStatus());
        int delivered = socketHandler.push(event.reportPublicId().toString(), ReportPushMessage.of(
                new ReportPushMessage(event.reportPublicId().toString(),
                        report.getStatus().name(), report.getScoreTotal())));
        if (delivered == 0) {
            // 결과는 저장돼 있습니다. 프론트가 늦게 붙은 경우는 상태 조회 API(#48)로 따라잡습니다.
            log.info("리포트 완료 push 수신자 없음 reportId={}", event.reportPublicId());
        }
    }

    /**
     * {@code FAILED} 로 정리하고 알립니다. 정리 자체가 실패해도 알림은 보냅니다.
     * 이 메서드는 예외를 밖으로 내보내지 않습니다.
     */
    private void fail(ReportRequestedEvent event, BusinessException cause) {
        ErrorCode errorCode = cause.getErrorCode();
        log.warn("리포트 생성 실패 sessionId={} errorCode={}", event.sessionId(), errorCode);
        try {
            reportWriter.fail(event.reportId(), errorCode);
        } catch (RuntimeException e) {
            log.error("리포트 FAILED 처리 실패 reportId={}", event.reportId(), e);
        }
        socketHandler.push(event.reportPublicId().toString(), ReportErrorPushMessage.of(
                new ReportErrorPushMessage(errorCode.name(), cause.getMessage(),
                        ReportFailurePolicy.isUserRetryable(errorCode))));
    }

    private void pushProgress(String reportId, AiReportTaskStatusResponse status) {
        ReportProgressStage stage = ReportProgressStage.from(status.stage());
        if (stage == null) {
            log.warn("모르는 리포트 stage 라 알리지 않습니다 stage={}", status.stage());
            return;
        }
        socketHandler.push(reportId, ReportProgressPushMessage.of(stage, status.progress()));
    }

    private BusinessException unexpected(RuntimeException e) {
        log.error("리포트 처리 중 예기치 못한 오류", e);
        BusinessException wrapped = new BusinessException(ErrorCode.INTERNAL_ERROR);
        wrapped.addSuppressed(e);
        return wrapped;
    }
}
