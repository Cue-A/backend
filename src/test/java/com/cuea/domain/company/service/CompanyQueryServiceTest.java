package com.cuea.domain.company.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.dto.response.CompanyDetailResponse;
import com.cuea.domain.company.dto.response.CompanyResponse;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.domain.company.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기업 조회 규칙을 DB 없이 검증합니다.
 *
 * <p>목록: verified 필터와 정렬은 쿼리(리포지토리)가 책임지므로 여기서는
 * <b>서비스가 verified·정렬 전용 프로젝션 메서드를 그대로 위임하는지</b>를 봅니다.
 * DTO 는 리포지토리의 생성자 프로젝션이 이미 채워 돌려주므로 서비스는 별도 매핑
 * 없이 그 결과를 통과시킵니다.
 *
 * <p>상세: 없는/verified=false 기업을 COMPANY_NOT_FOUND 로 거르는지와 DTO 매핑
 * (coreValues · talentProfile)을 봅니다. 실제 필터·정렬·프로젝션이 DB 에서
 * 작동하는지는 {@code CompanyRepositoryTest} 가 실제 PostgreSQL 로 확인합니다.
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
    void verified_정렬_전용_프로젝션_메서드로_조회한_결과를_그대로_돌려준다() {
        List<CompanyResponse> projected =
                List.of(new CompanyResponse(1L, "SK하이닉스"), new CompanyResponse(2L, "현대건설(주)"));
        when(companyRepository.findVerifiedCompanyList()).thenReturn(projected);

        List<CompanyResponse> result = service.list();

        // 서비스가 임의 조회(findAll)가 아니라 verified·정렬 전용 프로젝션 메서드를 쓴다.
        verify(companyRepository).findVerifiedCompanyList();
        assertThat(result)
                .extracting(CompanyResponse::companyId, CompanyResponse::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, "SK하이닉스"),
                        org.assertj.core.groups.Tuple.tuple(2L, "현대건설(주)"));
    }

    @Test
    void 기업이_없으면_빈_목록을_돌려준다() {
        when(companyRepository.findVerifiedCompanyList()).thenReturn(List.of());

        assertThat(service.list()).isEmpty();
    }

    @Test
    void 상세는_verified_전용_조회로_찾고_필드와_coreValues_를_매핑한다() {
        Company company = Company.builder()
                .companyId(1L)
                .companyName("SK하이닉스")
                .industry("반도체/메모리")
                .valuesFormat(ValuesFormat.MIXED)
                .coreValues(List.of(
                        Map.of("name", "One Team", "indicator", "자기 일처럼 처리한다."),
                        Map.of("name", "Innovation", "indicator", "과감히 도전한다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 8, 19))
                .verified(true)
                .talentProfile("함께 성장하는 인재")
                .build();
        when(companyRepository.findByCompanyIdAndVerifiedTrue(1L)).thenReturn(Optional.of(company));

        CompanyDetailResponse result = service.detail(1L);

        // 상세는 임의 조회(findById)가 아니라 verified 전용 조회를 쓴다.
        verify(companyRepository).findByCompanyIdAndVerifiedTrue(1L);
        assertThat(result.companyId()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("SK하이닉스");
        assertThat(result.industry()).isEqualTo("반도체/메모리");
        assertThat(result.valuesFormat()).isEqualTo(ValuesFormat.MIXED);
        assertThat(result.talentProfile()).isEqualTo("함께 성장하는 인재");
        assertThat(result.coreValues())
                .extracting(CompanyDetailResponse.CoreValue::name, CompanyDetailResponse.CoreValue::indicator)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("One Team", "자기 일처럼 처리한다."),
                        org.assertj.core.groups.Tuple.tuple("Innovation", "과감히 도전한다."));
    }

    @Test
    void 상세에서_talentProfile_이_없으면_null_로_내려준다() {
        when(companyRepository.findByCompanyIdAndVerifiedTrue(2L)).thenReturn(Optional.of(company(2L, "현대건설(주)")));

        assertThat(service.detail(2L).talentProfile()).isNull();
    }

    @Test
    void 없거나_verified_false_기업_상세는_COMPANY_NOT_FOUND() {
        // 서비스는 findByCompanyIdAndVerifiedTrue 의 빈 결과만 보므로, 없는 기업과
        // verified=false 기업이 여기서는 똑같이 빈 Optional 로 들어온다.
        when(companyRepository.findByCompanyIdAndVerifiedTrue(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMPANY_NOT_FOUND);
    }
}
