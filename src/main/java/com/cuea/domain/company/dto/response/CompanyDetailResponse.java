package com.cuea.domain.company.dto.response;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 기업 상세 화면이 쓰는 한 건. 목록 응답({@link CompanyResponse})과 달리
 * 인재상·핵심 가치까지 담습니다(Issue #87).
 *
 * <p>목록 API({@code GET /api/companies})는 면접 설정 드롭다운 계약이라
 * {@code companyId}·{@code name} 만 내보내는 것을 바꾸지 않습니다. 상세는 별도
 * 엔드포인트·별도 DTO 로 분리했습니다.
 *
 * <p>엔티티 필드명은 {@code companyName} 이지만 프론트 계약(목록과 동일)에 맞춰
 * {@code name} 으로 내보냅니다.
 *
 * <p>{@code sourceUrl}·{@code jobRequirements}·{@code collectedOn}·{@code verified}
 * 는 현재 화면 요구가 확정되지 않아 노출하지 않습니다(Issue #87 미확정 사항).
 * 필요해지면 그때 필드를 더합니다.
 */
@Schema(description = "기업 상세")
public record CompanyDetailResponse(

        @Schema(description = "기업 식별자", example = "1")
        Long companyId,

        @Schema(description = "기업명", example = "SK하이닉스")
        String name,

        @Schema(description = "업종", example = "반도체/메모리")
        String industry,

        @Schema(description = "핵심 가치 유형", example = "MIXED")
        ValuesFormat valuesFormat,

        @Schema(description = "핵심 가치 목록")
        List<CoreValue> coreValues,

        @Schema(description = "기업 인재상 설명. 없으면 null", nullable = true)
        String talentProfile
) {

    /**
     * 핵심 가치 한 항목. 화면에서 바로 쓰도록 구조화합니다.
     *
     * <p>엔티티의 {@code coreValues} 는 {@code [{"name": ..., "indicator": "..."}]}
     * 형태의 jsonb 입니다. 키 추출·파싱은 {@link Company#coreValueList()} 한 곳에서
     * 하고, 여기서는 그 결과({@link Company.CoreValue})를 화면용 필드로 옮기기만
     * 합니다. AI 프롬프트 조립용 {@code CompanyProfileFormatter} 도 같은
     * {@link Company#coreValueList()} 를 쓰되, 문자열("가치 이름 — 행동지표")로
     * 만드는 다른 용도라 이 DTO 를 재사용하지 않습니다(Issue #87).
     */
    @Schema(description = "핵심 가치 항목")
    public record CoreValue(

            @Schema(description = "가치 이름", example = "One Team")
            String name,

            @Schema(description = "행동지표", example = "본인 담당이 아니어도 전체를 생각하며 자기 일처럼 처리한다.")
            String indicator
    ) {
    }

    public static CompanyDetailResponse from(Company company) {
        return new CompanyDetailResponse(
                company.getCompanyId(),
                company.getCompanyName(),
                company.getIndustry(),
                company.getValuesFormat(),
                toCoreValues(company),
                company.getTalentProfile());
    }

    private static List<CoreValue> toCoreValues(Company company) {
        return company.coreValueList().stream()
                .map(cv -> new CoreValue(cv.name(), cv.indicator()))
                .toList();
    }
}
