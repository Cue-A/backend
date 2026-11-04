package com.cuea.domain.home.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 홈 "최근 리포트 요약" 목록.
 *
 * <p>배열을 그대로 내보내지 않고 객체로 감쌉니다. 홈 화면은 앞으로 섹션별 메타(전체 개수 등)가
 * 붙을 수 있어, 응답을 깨지 않고 필드를 더할 자리를 둡니다({@code docs/01-conventions.md} "응답은
 * 배열보다 객체"). 리포트가 없으면 {@code reports} 는 빈 배열입니다.
 */
@Schema(description = "홈 최근 리포트 요약 목록")
public record RecentReportListResponse(

        @Schema(description = "최근 리포트순. 없으면 빈 배열")
        List<RecentReportResponse> reports
) {

    public static RecentReportListResponse of(List<RecentReportResponse> reports) {
        return new RecentReportListResponse(reports);
    }
}
