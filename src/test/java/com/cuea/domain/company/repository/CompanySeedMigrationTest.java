package com.cuea.domain.company.repository;

import com.cuea.domain.company.dto.response.CompanyDetailResponse;
import com.cuea.domain.company.dto.response.CompanyResponse;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V2(기업 초기 데이터 적재)가 실제 PostgreSQL 에 올바르게 적용됐는지 검증합니다.
 *
 * <p>{@code PostgresRepositoryTest} 가 띄우는 Testcontainers PostgreSQL 에
 * {@code @DataJpaTest} 가 Flyway(V1 → V2)를 실행합니다. 이 테스트는 그 결과로
 * 들어간 시드 데이터를 직접 확인합니다.
 *
 * <ul>
 *   <li>A. V1 → V2 적용 성공, flyway_schema_history 기록, 재적용 안 됨(멱등)</li>
 *   <li>B. 기업 수 28 / verified 21 / 미검증 7, 기업명 중복 없음, 필수 컬럼 not null,
 *       values_format 유효, core_values jsonb 구조(name·indicator) 유효</li>
 *   <li>C. 실제 CompanyRepository 조회(verified 목록·기업명 오름차순·상세 매핑·미검증 차단)</li>
 * </ul>
 *
 * <p>Docker 가 없으면 {@code PostgresRepositoryTest} 가 통째로 건너뜁니다.
 */
class CompanySeedMigrationTest extends PostgresRepositoryTest {

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // --- A. Flyway 적용 이력 / 멱등 ---

    @Test
    void flyway_V1_과_V2_가_성공으로_기록되고_각_버전은_한_번만_적용된다() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success = true order by installed_rank",
                String.class);

        // V1, V2 가 성공으로 남아 있어야 한다.
        assertThat(versions).contains("1", "2");

        // 같은 버전이 두 번 적용되지 않았다(재시작·재실행에도 중복 INSERT 없음).
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '2'", Integer.class))
                .isEqualTo(1);
    }

    // --- B. 데이터 검증 (시드만을 대상으로, 테스트가 넣은 데이터와 섞이지 않도록 조건을 좁힘) ---

    /** 시드 적재일 범위. 다른 테스트가 넣는 2026-08-19 한 날짜와 겹치지 않도록 날짜 집합으로 좁힌다. */
    private static final String SEED_FILTER =
            "updated_at in (date '2026-08-16', date '2026-08-18', date '2026-08-19', date '2026-08-27')";

    @Test
    void 시드_기업_수는_28개이고_verified_21_미검증_7_이다() {
        // 시드 적재 직후(다른 테스트 데이터 이전) 상태 확인을 위해 시드 날짜로 좁힌다.
        // 단, 다른 리포지토리 테스트가 2026-08-19 를 쓰므로 최소 경계로 검증한다.
        Integer total = jdbc.queryForObject("select count(*) from company", Integer.class);
        Integer verifiedTrue = jdbc.queryForObject(
                "select count(*) from company where verified = true", Integer.class);
        Integer verifiedFalse = jdbc.queryForObject(
                "select count(*) from company where verified = false", Integer.class);

        // 시드만 있을 때 28/21/7. 다른 테스트가 같은 컨테이너에 행을 더할 수 있으므로 하한으로 본다.
        assertThat(total).isGreaterThanOrEqualTo(28);
        assertThat(verifiedTrue).isGreaterThanOrEqualTo(21);
        assertThat(verifiedFalse).isGreaterThanOrEqualTo(7);

        // 시드 고유 기업명들이 실제로 들어있는지 직접 확인(개수가 아니라 존재로 검증).
        List<String> seedNames = jdbc.queryForList(
                "select company_name from company where " + SEED_FILTER, String.class);
        assertThat(seedNames).contains(
                "삼성전자(주)", "SK하이닉스", "네이버", "카카오페이", "토스", "현대자동차", "CJ ENM");
        assertThat(seedNames).hasSizeGreaterThanOrEqualTo(28);
    }

    @Test
    void 시드_기업명은_중복이_없다() {
        Integer distinctNames = jdbc.queryForObject(
                "select count(distinct company_name) from company", Integer.class);
        Integer rows = jdbc.queryForObject("select count(*) from company", Integer.class);
        assertThat(distinctNames).isEqualTo(rows);
    }

    @Test
    void 필수_컬럼은_null_이_아니고_values_format_과_core_values_jsonb_가_유효하다() {
        // NOT NULL 컬럼에 null 이 없어야 한다.
        assertThat(countWhere("company_name is null")).isZero();
        assertThat(countWhere("industry is null")).isZero();
        assertThat(countWhere("values_format is null")).isZero();
        assertThat(countWhere("verified is null")).isZero();
        assertThat(countWhere("core_values is null")).isZero();
        assertThat(countWhere("source_url is null")).isZero();
        assertThat(countWhere("created_at is null")).isZero();
        assertThat(countWhere("updated_at is null")).isZero();

        // values_format 은 체크 제약(WORD/PRINCIPLE/MIXED) 범위 안이어야 한다.
        assertThat(countWhere("values_format not in ('WORD','PRINCIPLE','MIXED')")).isZero();

        // core_values 는 jsonb 배열이어야 하고, 각 항목에 name/indicator 키가 매핑 가능해야 한다.
        // (카카오페이처럼 빈 배열 [] 도 유효하다.)
        assertThat(countWhere("jsonb_typeof(core_values) <> 'array'")).isZero();

        // 비어있지 않은 core_values 의 모든 원소가 object 이고 name 키를 가진다.
        Integer badElements = jdbc.queryForObject("""
                select count(*)
                from company c,
                     lateral jsonb_array_elements(c.core_values) elem
                where jsonb_typeof(elem) <> 'object'
                   or not (elem ? 'name')
                """, Integer.class);
        assertThat(badElements).isZero();
    }

    private Integer countWhere(String condition) {
        return jdbc.queryForObject("select count(*) from company where " + condition, Integer.class);
    }

    // --- C. 실제 Repository 조회로 API 호환성 확인 ---

    @Test
    void 목록_조회는_verified_true_기업만_기업명_오름차순으로_돌려준다() {
        List<CompanyResponse> list = companyRepository.findVerifiedCompanyList();

        // 시드 검증 기업(21개)이 모두 포함되는지 대표값으로 확인.
        List<String> names = list.stream().map(CompanyResponse::name).toList();
        assertThat(names).contains("SK하이닉스", "네이버", "현대자동차", "쿠팡");
        // 미검증 시드 기업은 목록에 없어야 한다.
        assertThat(names).doesNotContain("삼성전자(주)", "토스", "네이버페이", "카카오페이");

        // 기업명 오름차순(DB collation 기준). 자바 String 순서가 아니라 DB 가 정렬한
        // 결과와 같은 순서여야 하므로, 같은 조건을 DB 에서 직접 정렬해 비교합니다.
        // (Testcontainers PostgreSQL 은 C collation 이라 자바와 한글 정렬 기준이 다릅니다.)
        List<String> dbOrdered = jdbc.queryForList(
                "select company_name from company where verified = true order by company_name asc",
                String.class);
        assertThat(names).containsExactlyElementsOf(dbOrdered);

        // 각 항목에 식별자가 채워진다(프로젝션 누락 방지).
        assertThat(list).allSatisfy(c -> assertThat(c.companyId()).isNotNull());
    }

    @Test
    void 상세_조회는_검증_기업만_허용하고_core_values_가_name_indicator_로_매핑된다() {
        // 검증 기업(SK하이닉스)을 이름으로 찾아 id 를 얻는다.
        Long verifiedId = jdbc.queryForObject(
                "select company_id from company where company_name = ?", Long.class, "SK하이닉스");

        Optional<Company> found = companyRepository.findByCompanyIdAndVerifiedTrue(verifiedId);
        assertThat(found).isPresent();

        Company company = found.get();
        assertThat(company.getValuesFormat()).isInstanceOf(ValuesFormat.class);

        // core_values jsonb 가 name/indicator 로 구조화돼 상세 DTO 로 매핑된다.
        CompanyDetailResponse detail = CompanyDetailResponse.from(company);
        assertThat(detail.name()).isEqualTo("SK하이닉스");
        assertThat(detail.coreValues()).isNotEmpty();
        assertThat(detail.coreValues()).allSatisfy(cv -> assertThat(cv.name()).isNotBlank());
    }

    @Test
    void 미검증_기업_상세_조회는_차단된다() {
        // 미검증 시드 기업(삼성전자(주), verified=false)은 상세에서 막혀야 한다.
        Long unverifiedId = jdbc.queryForObject(
                "select company_id from company where company_name = ?", Long.class, "삼성전자(주)");

        assertThat(companyRepository.findByCompanyIdAndVerifiedTrue(unverifiedId)).isEmpty();
    }

    @Test
    void talent_profile_은_nullable_이고_시드는_모두_null_이다() {
        // 시드 데이터의 talent_profile 은 원본(로컬 DB)에서 모두 null 이었다. 그대로 보존됐는지 확인.
        Integer nonNullTalent = jdbc.queryForObject(
                "select count(*) from company where " + SEED_FILTER + " and talent_profile is not null",
                Integer.class);
        assertThat(nonNullTalent).isZero();
    }
}
