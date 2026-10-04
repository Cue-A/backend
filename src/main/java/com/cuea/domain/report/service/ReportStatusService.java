package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.report.dto.response.ReportStatusResponse;
import com.cuea.domain.report.dto.response.ReportRetryInfo;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportRetryStatus;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.domain.report.service.ReportProgressStore.ReportProgress;
import com.cuea.infrastructure.ai.AiReportResultReader;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import com.cuea.infrastructure.websocket.message.ReportProgressStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 리포트 생성 상태 조회. <b>본인 리포트만 보입니다.</b>
 *
 * <p>진행률과 결과는 WebSocket 으로 나가지만, 소켓에 늦게 붙거나 새로고침하면 그 사이의
 * 메시지를 놓칩니다. 프론트는 소켓 연결 직후 이걸 한 번 불러 상태를 맞춥니다.
 *
 * <p>상태는 DB 기준이고, PROCESSING 일 때만 {@link ReportProgressStore} 에서 마지막 단계를
 * 붙입니다. 끝난 리포트에 지우지 못한 진행 단계가 남아 있어도 새지 않습니다. 분석이
 * 끝나면(COMPLETED · PARTIAL · FAILED) stage · progress 는 null 입니다. 단 PARTIAL 의 실패 축을
 * 재시도하는 중이면 재시도의 진행 단계를 붙입니다.
 *
 * <p>점수와 본문은 내려주지 않습니다. 상세 조회 API 몫입니다.
 *
 * <p>조회 한 번이라 트랜잭션을 걸지 않습니다. 지연 로딩 연관을 건드리지 않고, Redis 조회
 * 동안 DB 커넥션을 붙들 이유가 없습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportStatusService {

    private final ReportRepository reportRepository;
    private final ReportProgressStore progressStore;
    private final AiReportResultReader resultReader;

    public ReportStatusResponse getStatus(String userId, String reportId) {
        Report report = reportRepository.findByPublicIdAndSession_User_UserId(parse(reportId), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

        // UUID.fromString 은 대문자·축약형도 받으므로 응답과 Redis 키는 저장된 값으로 씁니다.
        String publicId = report.getPublicId().toString();
        return switch (report.getStatus()) {
            case PROCESSING -> withProgress(report, publicId);
            case COMPLETED -> response(report, publicId, null, null, null, null, null);
            // 재시도 중이면 리포트는 PARTIAL 그대로 두고 재시도의 진행 단계를 붙입니다.
            case PARTIAL -> report.getRetryStatus() == ReportRetryStatus.PROCESSING
                    ? withProgress(report, publicId)
                    : response(report, publicId, null, null, null, null, null);
            case FAILED -> response(report, publicId, null, null, report.getErrorCode(),
                    ReportFailurePolicy.messageOf(report.getErrorCode()),
                    ReportFailurePolicy.isUserRetryable(report.getErrorCode()));
        };
    }

    private ReportStatusResponse withProgress(Report report, String publicId) {
        Optional<ReportProgress> progress = progressStore.find(publicId);
        return response(report, publicId,
                progress.map(ReportProgress::stage).orElse(null),
                progress.map(ReportProgress::progress).orElse(null),
                null, null, null);
    }

    /**
     * 재시도 축은 저장된 결과의 {@code overall.axes_failed} 입니다. 재시도한 적이 없으면 읽지 않습니다.
     * 읽지 못해도 상태 조회는 실패시키지 않고 축을 비워 둡니다. 가벼운 따라잡기용이기 때문입니다.
     */
    private ReportRetryInfo retryInfo(Report report) {
        if (report.getRetryStatus() == null) {
            return null;
        }
        List<String> axes = List.of();
        try {
            if (report.getReportData() != null) {
                AiReportResult.Overall overall = resultReader.read(report.getReportData()).overall();
                if (overall != null && overall.axesFailed() != null) {
                    axes = overall.axesFailed();
                }
            }
        } catch (BusinessException e) {
            log.warn("재시도 축을 읽지 못했습니다 reportId={}", report.getPublicId(), e);
        }
        return ReportRetryInfos.of(report, axes);
    }

    /**
     * {@code getSession().getSessionId()} 는 지연 로딩 프록시의 식별자라 세션을 읽지 않습니다.
     * 트랜잭션 밖이어도 괜찮습니다.
     */
    private ReportStatusResponse response(Report report, String publicId,
                                          ReportProgressStage stage, Double progress,
                                          String errorCode, String message, Boolean retryable) {
        return new ReportStatusResponse(
                publicId, report.getSession().getSessionId(), report.getStatus(),
                stage, progress, errorCode, message, retryable, retryInfo(report),
                report.getCreatedAt(), report.getCompletedAt());
    }

    /** UUID 가 아닌 값도 없는 리포트와 같은 404 입니다. 400 으로 구별해 줄 이유가 없습니다. */
    private UUID parse(String reportId) {
        try {
            return UUID.fromString(reportId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
        }
    }
}
