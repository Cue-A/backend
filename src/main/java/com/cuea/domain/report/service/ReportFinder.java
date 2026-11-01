package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 외부 식별자({@code reportId})로 <b>본인 리포트</b>를 찾습니다. 상태 조회 · 상세 조회 · 재시도가
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

    private UUID parse(String reportId) {
        try {
            return UUID.fromString(reportId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
        }
    }
}
