package com.cuea.domain.home.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.home.dto.response.RecentReportListResponse;
import com.cuea.domain.home.dto.response.RecentReportResponse;
import com.cuea.domain.report.repository.RecentReportRow;
import com.cuea.domain.report.service.ReportSummaryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 홈 대시보드 "최근 리포트 요약". 본인의 열람 가능한 리포트를 연습을 최근에 본 순서로 몇 건
 * 돌려줍니다(Issue #107).
 *
 * <p>리포트 조회·소유권 스코프·상태 필터는 리포트 도메인의 {@link ReportSummaryQueryService} 에
 * 맡기고(도메인 간 참조는 service 레벨에서만 — docs/01-conventions.md), 여기서는 limit 검증과
 * 응답 매핑만 합니다.
 */
@Service
@RequiredArgsConstructor
public class RecentReportQueryService {

    static final int DEFAULT_LIMIT = 3;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 10;

    private final ReportSummaryQueryService reportSummaryQueryService;

    /**
     * 최근 리포트 요약. {@code limit} 은 1~10 이며 비우면 {@value #DEFAULT_LIMIT} 건입니다.
     * 범위를 벗어나면 {@link ErrorCode#INVALID_REQUEST}(400) 입니다.
     */
    @Transactional(readOnly = true)
    public RecentReportListResponse recentReports(String userId, Integer limit) {
        int size = sizeOf(limit);

        List<RecentReportResponse> reports = reportSummaryQueryService
                .findRecentViewable(userId, size)
                .stream()
                .map(RecentReportQueryService::toResponse)
                .toList();

        return RecentReportListResponse.of(reports);
    }

    /**
     * 연습 기록 목록({@code size})과 달리 잘못된 값을 기본값으로 되돌리지 않고 400 으로 막습니다.
     * 홈 카드는 3건 안팎의 고정 조회라, 0 이나 음수·과도한 값은 호출 쪽 버그로 보는 편이 안전합니다.
     */
    private static int sizeOf(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "limit 은 %d~%d 사이여야 합니다".formatted(MIN_LIMIT, MAX_LIMIT));
        }
        return limit;
    }

    private static RecentReportResponse toResponse(RecentReportRow row) {
        return new RecentReportResponse(
                row.publicId().toString(),
                row.companyName(),
                row.jobRole(),
                row.questionCount(),
                row.status(),
                row.scoreTotal(),
                row.practicedOn());
    }
}
