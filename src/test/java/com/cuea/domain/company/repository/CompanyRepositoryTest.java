package com.cuea.domain.company.repository;

import com.cuea.domain.company.dto.response.CompanyResponse;
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
 * <p>조회는 {@code CompanyResponse} 생성자 프로젝션({@code select new ...})이라
 * 엔티티 전체가 아니라 {@code company_id}·{@code company_name} 두 컬럼만
 * select 합니다. 실제 PostgreSQL 에서 이 프로젝션 JPQL 이 깨지지 않는지도 함께
 * 검증합니다.
 *
 * <p>테이블에 {@code core_values} jsonb 등이 있어 H2 를 쓰지 못합니다. Docker 가
 * 없으면 {@code PostgresRepositoryTest} 가 건너뜁니다.
 *
 * <p><b>Flyway V2(기업 초기 데이터 적재)로 company 테이블에 시드 28건이 들어있습니다.</b>
 * 이 테스트는 싱글턴 컨테이너를 공유하고 시드는 커밋돼 롤백되지 않으므로, 전역
 * {@code containsExactly}/{@code isEmpty} 로 단정하면 시드 때문에 깨집니다.
 * 그래서 이 테스트가 넣은 행만 고유 접두사({@code PREFIX})로 가려내어 검증합니다.
 * verified 필터·기업명 오름차순 정렬·프로젝션이라는 검증 의도는 그대로 둡니다.
 */
class CompanyRepositoryTest extends PostgresRepositoryTest {

    /** 이 테스트가 넣은 행만 시드(V2)와 구분하기 위한 고유 접두사. */
    private static final String PREFIX = "ZZTEST_" + System.nanoTime() + "_";

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TestEntityManager em;

    private Company company(String name, boolean verified) {
        return Company.builder()
                .companyName(PREFIX + name)
                .industry("반도체/메모리")
                .valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전", "indicator", "두려워하지 않는다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 8, 19))
                .verified(verified)
                .build();
    }

    /** 이 테스트가 넣은 행(접두사)만 남긴 이름 목록. 시드·다른 테스트 데이터는 제외합니다. */
    private List<String> myNames(List<CompanyResponse> result) {
        return result.stream()
                .map(CompanyResponse::name)
                .filter(name -> name.startsWith(PREFIX))
                .toList();
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

        List<CompanyResponse> result = companyRepository.findVerifiedCompanyList();

        // 이 테스트가 넣은 verified=true 3곳만, 기업명 오름차순.
        // (company 테이블에는 V2 시드가 함께 있으므로 접두사로 가려냅니다.)
        // 접두사가 같은 세 이름은 뒷부분(가나/나다/다라)만 다르고, 이 글자들은
        // DB collation 과 자바 문자열 순서가 일치하므로 containsExactly 로 순서를 단정합니다.
        assertThat(myNames(result))
                .containsExactly(PREFIX + "가나기업", PREFIX + "나다기업", PREFIX + "다라기업");
        // 결과 전체가 DB 의 order by company_name 과 같은 순서인지 확인합니다.
        // (자바 String 순서가 아니라 DB collation 기준이어야 하므로, 같은 조건을
        //  DB 에서 직접 정렬해 비교합니다 — Testcontainers 는 C collation 이라 자바와
        //  한글 정렬 기준이 다릅니다.)
        List<String> dbOrdered = em.getEntityManager()
                .createNativeQuery("select company_name from company where verified = true order by company_name asc")
                .getResultList();
        assertThat(result)
                .extracting(CompanyResponse::name)
                .containsExactlyElementsOf(dbOrdered);
        // 내 미검증 기업은 빠져야 합니다.
        assertThat(result).noneMatch(c -> c.name().equals(PREFIX + "미검증기업"));
        // verified=false 기업은 결과에 하나도 없어야 합니다(시드의 미검증 7건 포함).
        // 프로젝션이 companyId 도 함께 채우는지 확인합니다(식별자 누락 방지).
        assertThat(result).allSatisfy(c -> assertThat(c.companyId()).isNotNull());
    }

    @Test
    void verified_기업이_없으면_결과에_그_기업이_없다() {
        // 미검증 기업만 넣으면, 이 테스트가 넣은 행 중에는 verified=true 가 없어야 합니다.
        // (company 테이블 전역은 V2 시드로 비어있지 않으므로 접두사로 가려냅니다.)
        em.persist(company("미검증기업", false));
        em.flush();

        assertThat(myNames(companyRepository.findVerifiedCompanyList())).isEmpty();
    }
}
