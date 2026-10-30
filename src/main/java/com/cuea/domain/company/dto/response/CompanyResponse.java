package com.cuea.domain.company.dto.response;

import com.cuea.domain.company.entity.Company;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 면접 설정 화면의 기업 선택 목록 한 건.
 *
 * <p>프론트가 드롭다운을 그리는 데 쓰는 {@code companyId} · {@code name} 만
 * 내보냅니다. 인재상({@code coreValues})·업종({@code industry})·출처
 * ({@code sourceUrl}) 같은 내부 필드는 현재 화면이 쓰지 않으므로 노출하지
 * 않습니다(Issue #85). 질문 생성용 기업 정보는 면접 시작 때 서버가
 * {@code CompanyProfileFormatter} 로 조립해 AI 에 넘기며, 이 응답과는 별개입니다.
 *
 * <p>엔티티 필드명은 {@code companyName} 이지만 프론트 계약에 맞춰 {@code name}
 * 으로 내보냅니다.
 */
@Schema(description = "기업 목록 항목")
public record CompanyResponse(

        @Schema(description = "기업 식별자", example = "1")
        Long companyId,

        @Schema(description = "기업명", example = "SK하이닉스")
        String name
) {

    public static CompanyResponse from(Company company) {
        return new CompanyResponse(company.getCompanyId(), company.getCompanyName());
    }
}
