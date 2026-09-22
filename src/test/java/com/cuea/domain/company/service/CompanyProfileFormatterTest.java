package com.cuea.domain.company.service;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 계약(질문 생성 API 계약 6장)의 {@code company_profile_override} 형식과
 * 일치하는지 검증합니다.
 */
class CompanyProfileFormatterTest {

    private final CompanyProfileFormatter formatter = new CompanyProfileFormatter();

    @Test
    void 인재상을_기업명_업종_핵심가치_형식으로_조립한다() {
        Company company = Company.builder()
                .companyId(1L)
                .companyName("SK하이닉스")
                .industry("반도체/메모리")
                .valuesFormat(ValuesFormat.MIXED)
                .coreValues(List.of(
                        Map.of("name", "Bar Raising", "indicator", "이만하면 됐다 싶은 지점에서 한 번 더 고민한다."),
                        Map.of("name", "One Team", "indicator", "본인 담당이 아니어도 전체를 생각한다.")))
                .jobRequirements(null)
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 8, 19))
                .verified(true)
                .build();

        String result = formatter.format(company);

        assertThat(result).isEqualTo("""
                SK하이닉스 (반도체/메모리)
                핵심 가치
                  Bar Raising — 이만하면 됐다 싶은 지점에서 한 번 더 고민한다.
                  One Team — 본인 담당이 아니어도 전체를 생각한다.""");
    }

    @Test
    void 핵심가치가_하나여도_동작한다() {
        Company company = Company.builder()
                .companyId(2L)
                .companyName("현대건설(주)")
                .industry("종합건설 · 플랜트")
                .valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전", "indicator", "새로운 시도를 두려워하지 않는다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 1, 1))
                .verified(true)
                .build();

        String result = formatter.format(company);

        assertThat(result).contains("현대건설(주) (종합건설 · 플랜트)")
                .contains("도전 — 새로운 시도를 두려워하지 않는다.");
    }
}
