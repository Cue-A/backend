package com.cuea.domain.company.service;

import com.cuea.domain.company.dto.response.CompanyResponse;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.domain.company.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기업 목록 조회 규칙을 DB 없이 검증합니다.
 *
 * <p>verified 필터와 정렬은 쿼리(리포지토리)가 책임지므로 여기서는 <b>리포지토리가
 * verified·정렬 전용 메서드를 부르는지</b>와 <b>DTO 매핑(companyId · name)</b>을
 * 봅니다. 실제 필터·정렬이 DB 에서 작동하는지는 {@code CompanyRepositoryTest} 가
 * 실제 PostgreSQL 로 확인합니다.
 */
class CompanyQueryServiceTest {

    private CompanyRepository companyRepository;
    private CompanyQueryService service;

    @BeforeEach
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        service = new CompanyQueryService(companyRepository);
    }

    private Company company(Long id, String name) {
        return Company.builder()
                .companyId(id)
                .companyName(name)
                .industry("반도체/메모리")
                .valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전", "indicator", "두려워하지 않는다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 8, 19))
                .verified(true)
                .build();
    }

    @Test
    void verified_정렬_전용_메서드로_조회하고_companyId_와_name_으로_매핑한다() {
        when(companyRepository.findByVerifiedTrueOrderByCompanyNameAsc())
                .thenReturn(List.of(company(1L, "SK하이닉스"), company(2L, "현대건설(주)")));

        List<CompanyResponse> result = service.list();

        // 서비스가 임의 조회(findAll)가 아니라 verified·정렬 전용 메서드를 쓴다.
        verify(companyRepository).findByVerifiedTrueOrderByCompanyNameAsc();
        assertThat(result)
                .extracting(CompanyResponse::companyId, CompanyResponse::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, "SK하이닉스"),
                        org.assertj.core.groups.Tuple.tuple(2L, "현대건설(주)"));
    }

    @Test
    void 기업이_없으면_빈_목록을_돌려준다() {
        when(companyRepository.findByVerifiedTrueOrderByCompanyNameAsc())
                .thenReturn(List.of());

        assertThat(service.list()).isEmpty();
    }
}
