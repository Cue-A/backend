package com.cuea.domain.company.repository;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verified 필터와 기업명 정렬이 실제 PostgreSQL 에서 작동하는지 봅니다.
 * mock 리포지토리로는 WHERE/ORDER BY 가 검증되지 않습니다(docs/01-conventions.md).
 *
 * <p>{@code core_values} 가 jsonb 라 H2 를 쓰지 못합니다. Docker 가 없으면
 * {@code PostgresRepositoryTest} 가 건너뜁니다.
 */
class CompanyRepositoryTest extends PostgresRepositoryTest {

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TestEntityManager em;

    private Company company(String name, boolean verified) {
        return Company.builder()
                .companyName(name)
                .industry("반도체/메모리")
                .valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전", "indicator", "두려워하지 않는다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 8, 19))
                .verified(verified)
                .build();
    }

    @Test
    void verified_true_기업만_기업명_오름차순으로_돌려준다() {
        // 정렬 검증은 교차 문자(한글↔ASCII) 순서가 DB collation 에 따라 달라지므로
        // 한글 이름만으로 모호하지 않게 구성합니다. verified=false 는 빠져야 합니다.
        em.persist(company("다라기업", true));
        em.persist(company("가나기업", true));
        em.persist(company("나다기업", true));
        em.persist(company("미검증기업", false));
        em.flush();

        List<Company> result = companyRepository.findByVerifiedTrueOrderByCompanyNameAsc();

        // verified=true 3곳만, 기업명 오름차순.
        assertThat(result)
                .extracting(Company::getCompanyName)
                .containsExactly("가나기업", "나다기업", "다라기업");
        // 결과가 실제로 오름차순인지(DB collation 기준)도 함께 확인합니다.
        assertThat(result)
                .extracting(Company::getCompanyName)
                .isSorted();
        assertThat(result).noneMatch(c -> c.getCompanyName().equals("미검증기업"));
    }

    @Test
    void verified_기업이_없으면_빈_목록을_돌려준다() {
        em.persist(company("미검증기업", false));
        em.flush();

        assertThat(companyRepository.findByVerifiedTrueOrderByCompanyNameAsc()).isEmpty();
    }
}
