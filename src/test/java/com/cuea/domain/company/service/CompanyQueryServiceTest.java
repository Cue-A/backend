package com.cuea.domain.company.service;

import com.cuea.domain.company.dto.response.CompanyResponse;
import com.cuea.domain.company.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기업 목록 조회 규칙을 DB 없이 검증합니다.
 *
 * <p>verified 필터와 정렬은 쿼리(리포지토리)가 책임지므로 여기서는 <b>서비스가
 * verified·정렬 전용 프로젝션 메서드를 그대로 위임하는지</b>를 봅니다. DTO 는
 * 리포지토리의 생성자 프로젝션이 이미 채워 돌려주므로 서비스는 별도 매핑 없이
 * 그 결과를 통과시킵니다. 실제 필터·정렬이 DB 에서 작동하는지는
 * {@code CompanyRepositoryTest} 가 실제 PostgreSQL 로 확인합니다.
 */
class CompanyQueryServiceTest {

    private CompanyRepository companyRepository;
    private CompanyQueryService service;

    @BeforeEach
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        service = new CompanyQueryService(companyRepository);
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
}
