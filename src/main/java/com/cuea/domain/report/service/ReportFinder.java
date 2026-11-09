package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 외부 식별자({@code reportId})로 <b>본인 리포트</b>를 찾습니다. 상태 조회 · 상세 조회 · 재시도 · 회차 비교가
 * 같은 규칙을 쓰도록 한곳에 둡니다.
 *
 * <p>없는 것 · 남의 것 · UUID 가 아닌 값은 모두 404 {@code REPORT_NOT_FOUND} 입니다. 형식 오류를
 * 400 으로 구별해 주면 "UUID 형태면 존재할 수도 있다"는 정보가 됩니다.
 *
 * <p>예외는 공유 링크({@link #getShared}) 하나입니다. 공개 조회라 소유자를 보지 않고 공유 토큰으로만 찾습니다.
 */
@Component
@RequiredArgsConstructor
class ReportFinder {

    private final ReportRepository reportRepository;

    Report getOwned(String userId, String reportId) {
        UUID publicId = parse(reportId, ErrorCode.REPORT_NOT_FOUND);
        return reportRepository.findByPublicIdAndSession_User_UserId(publicId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    }

    /**
     * 공유 링크의 리포트. <b>공개 조회 전용이라 소유자를 보지 않습니다.</b> 없는 링크 · 해제된 링크 ·
     * UUID 가 아닌 값은 모두 404 {@code SHARED_REPORT_NOT_FOUND} 입니다.
     */
    Report getShared(String shareId) {
        return reportRepository.findByShareIdForPublic(parse(shareId, ErrorCode.SHARED_REPORT_NOT_FOUND))
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARED_REPORT_NOT_FOUND));
    }

    /**
     * 끝난(COMPLETED · PARTIAL) 본인 리포트. 회차 비교가 쓰고, 규칙은 상세 조회({@link ReportQueryService})와 같습니다.
     * 진행 중이면 202 {@code REPORT_NOT_READY}, 실패했으면 409 {@code REPORT_FAILED} 입니다.
     */
    Report getFinished(String userId, String reportId) {
        Report report = getOwned(userId, reportId);
        // switch 식이라 ReportStatus 가 늘면 여기서 컴파일이 깨집니다. 새 상태를 빠뜨리지 않게 하려는 것입니다.
        return switch (report.getStatus()) {
            case PROCESSING -> throw new BusinessException(ErrorCode.REPORT_NOT_READY);
            case FAILED -> throw new BusinessException(ErrorCode.REPORT_FAILED);
            case COMPLETED, PARTIAL -> report;
        };
    }

    /** 끝난 리포트의 AI 결과 원본. 저장 전에 모양을 확인하므로 정상이라면 비지 않고, 비었으면 우리 쪽 문제라 500 입니다. */
    static Map<String, Object> reportDataOf(Report report) {
        if (report.getReportData() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "끝난 리포트에 결과가 없습니다");
        }
        return report.getReportData();
    }

    private UUID parse(String id, ErrorCode notFound) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(notFound);
        }
    }
}
