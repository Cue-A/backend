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
 */
@Component
@RequiredArgsConstructor
class ReportFinder {

    private final ReportRepository reportRepository;

    Report getOwned(String userId, String reportId) {
        return reportRepository.findByPublicIdAndSession_User_UserId(parse(reportId), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    }

    /**
     * 끝난(COMPLETED · PARTIAL) 본인 리포트. 결과를 읽는 조회(상세 · 회차 비교)가 같은 규칙을 씁니다.
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

    private UUID parse(String reportId) {
        try {
            return UUID.fromString(reportId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
        }
    }
}
