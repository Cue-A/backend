package com.cuea.domain.company.dto.response;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.UserCompanyInterest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 내 관심 기업 목록의 한 건(Issue #88).
 *
 * <p>목록 표시에 필요한 최소 정보({@code companyId}·{@code name})만 담습니다.
 * 프론트는 이 {@code companyId} 로 "이 기업이 관심 등록돼 있는지"를 판단합니다
 * (기업 목록 API 에 {@code isInterested} 를 넣지 않는 대신).
 *
 * <p>관심 연결 자체의 식별자({@code UserCompanyInterest.id})는 내보내지 않습니다.
 * 등록·해제는 {@code companyId} 로만 하므로 노출할 이유가 없습니다.
 */
@Schema(description = "관심 기업 목록 항목")
public record CompanyInterestResponse(

        @Schema(description = "기업 식별자", example = "1")
        Long companyId,

        @Schema(description = "기업명", example = "SK하이닉스")
        String name
) {

    public static CompanyInterestResponse from(UserCompanyInterest interest) {
        Company company = interest.getCompany();
        return new CompanyInterestResponse(company.getCompanyId(), company.getCompanyName());
    }
}
