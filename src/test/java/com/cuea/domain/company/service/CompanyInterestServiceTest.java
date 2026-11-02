package com.cuea.domain.company.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.dto.response.CompanyInterestResponse;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.UserCompanyInterest;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.domain.company.repository.CompanyRepository;
import com.cuea.domain.company.repository.UserCompanyInterestRepository;
import com.cuea.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관심 기업 등록·조회·해제의 비즈니스 규칙을 DB 없이 봅니다.
 *
 * <p>여기서는 verified 검증(없는/미검증 기업 404), 등록이 멱등 삽입
 * ({@code insertIfAbsent})에 위임하는지, 조회·삭제가 소유자 스코프 메서드에
 * 위임하는지를 봅니다. 멱등·동시성·소유자 스코프가 실제 PostgreSQL 에서 작동하고
 * UNIQUE 경합에도 트랜잭션이 깨지지 않는지는
 * {@code UserCompanyInterestRepositoryTest} · {@code CompanyInterestServiceIntegrationTest}
 * 가 실제 DB 로 확인합니다(mock 으로는 rollback-only 동작이 드러나지 않습니다).
 */
class CompanyInterestServiceTest {

    private UserCompanyInterestRepository interestRepository;
    private CompanyRepository companyRepository;
    private CompanyInterestService service;

    @BeforeEach
    void setUp() {
        interestRepository = mock(UserCompanyInterestRepository.class);
        companyRepository = mock(CompanyRepository.class);
        service = new CompanyInterestService(interestRepository, companyRepository);
    }

    private Company verifiedCompany(Long id) {
        return Company.builder()
                .companyId(id)
                .companyName("기업" + id)
                .industry("반도체/메모리")
                .valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전", "indicator", "두려워하지 않는다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 8, 19))
                .verified(true)
                .build();
    }

    @Test
    void verified_기업이면_멱등_삽입에_위임한다() {
        when(companyRepository.findByCompanyIdAndVerifiedTrue(1L)).thenReturn(Optional.of(verifiedCompany(1L)));

        service.register("user-1", 1L);

        // 삽입 자체가 ON CONFLICT DO NOTHING 이라 서비스는 반환값으로 분기하지 않는다.
        verify(interestRepository).insertIfAbsent("user-1", 1L);
    }

    @Test
    void 없거나_verified_false_기업_등록은_COMPANY_NOT_FOUND_이고_삽입하지_않는다() {
        when(companyRepository.findByCompanyIdAndVerifiedTrue(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register("user-1", 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMPANY_NOT_FOUND);
        verify(interestRepository, never()).insertIfAbsent(anyString(), anyLong());
    }

    @Test
    void 목록은_소유자_스코프_최신순_조회를_위임하고_DTO_로_매핑한다() {
        UserCompanyInterest interest = UserCompanyInterest.builder()
                .user(User.create(null, "면접자"))
                .company(verifiedCompany(3L))
                .build();
        when(interestRepository.findByUser_UserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of(interest));

        List<CompanyInterestResponse> result = service.list("user-1");

        verify(interestRepository).findByUser_UserIdOrderByCreatedAtDesc("user-1");
        assertThat(result)
                .extracting(CompanyInterestResponse::companyId, CompanyInterestResponse::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3L, "기업3"));
    }

    @Test
    void 관심이_없으면_빈_목록() {
        when(interestRepository.findByUser_UserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of());

        assertThat(service.list("user-1")).isEmpty();
    }

    @Test
    void 해제는_소유자_스코프_삭제를_위임한다() {
        service.remove("user-1", 1L);

        verify(interestRepository).deleteByUser_UserIdAndCompany_CompanyId("user-1", 1L);
    }

    @Test
    void 없던_관심을_해제해도_예외없이_성공한다() {
        // 리포지토리 delete 는 0 을 돌려줄 뿐 예외가 아니다. 서비스는 반환값을 쓰지 않는다.
        when(interestRepository.deleteByUser_UserIdAndCompany_CompanyId("user-1", 1L)).thenReturn(0);

        assertThatCode(() -> service.remove("user-1", 1L)).doesNotThrowAnyException();
    }
}
