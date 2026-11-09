package com.cuea.domain.report.dto.response;

import com.cuea.domain.report.entity.Report;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 공유 링크. 공유 생성 응답이자 상세 조회의 {@code share} 입니다.
 *
 * <p>링크 URL 은 프론트가 {@code shareId} 로 만듭니다. 서버는 프론트 주소를 모릅니다.
 */
@Schema(description = "공유 링크. 공유하지 않았으면 null")
public record ReportShareInfo(

        @Schema(description = "공개 리포트 조회(GET /api/public/reports/{shareId})에 씁니다. reportId 와 다른 값이고 해제 후 다시 공유하면 바뀝니다")
        String shareId,

        OffsetDateTime sharedAt
) {

    /** 공유하지 않은 리포트면 null. */
    public static ReportShareInfo of(Report report) {
        if (report.getShareId() == null) {
            return null;
        }
        return new ReportShareInfo(report.getShareId().toString(), report.getSharedAt());
    }
}
