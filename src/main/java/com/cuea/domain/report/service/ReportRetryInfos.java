package com.cuea.domain.report.service;

import com.cuea.domain.report.dto.response.ReportRetryInfo;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportRetryStatus;

import java.util.List;

/**
 * {@link ReportRetryInfo} 를 만듭니다. 실패 문구와 재시도 가능 여부가 {@link ReportFailurePolicy}
 * 규칙을 따라야 해서 같은 패키지에 둡니다.
 */
final class ReportRetryInfos {

    private ReportRetryInfos() {
    }

    /** 재시도한 적이 없거나 성공했으면 null. */
    static ReportRetryInfo of(Report report, List<String> axes) {
        ReportRetryStatus status = report.getRetryStatus();
        if (status == null) {
            return null;
        }
        List<String> retryAxes = axes == null ? List.of() : axes;
        if (status == ReportRetryStatus.PROCESSING) {
            return new ReportRetryInfo(status, retryAxes, null, null, null);
        }
        String errorCode = report.getErrorCode();
        return new ReportRetryInfo(status, retryAxes, errorCode,
                ReportFailurePolicy.messageOf(errorCode), ReportFailurePolicy.isUserRetryable(errorCode));
    }
}
