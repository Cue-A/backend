package com.cuea.domain.company.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 를 <b>여러 번 실행</b>해 V2 기업 시드의 적용·재적용 멱등성을 확인합니다.
 *
 * <p>{@code @DataJpaTest}({@code CompanySeedMigrationTest})는 Spring 컨텍스트 캐시로
 * Flyway 가 JVM 당 한 번만 도는 것을 보지만, 여기서는 깨끗한 컨테이너에 Flyway API 로 직접
 * 올립니다. <b>전체 마이그레이션 개수와 무관하게</b> V2 시드가 정확히 한 번만 적용되는지를
 * 봅니다: 먼저 {@code target("2")} 로 V1→V2 만 올리고(이후 V3… 버전 수와 독립), 이어서 target
 * 없이 전체 migrate() 를 돌려 V2 는 재적용되지 않고 뒤 버전만 올라가는지, 다시 migrate() 하면
 * 아무것도 적용되지 않는지 확인합니다.
 *
 * <p>이 테스트는 자체 컨테이너를 쓰므로 싱글턴({@code PostgresRepositoryTest})과 분리됩니다.
 * Docker 가 없으면 건너뜁니다({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@Testcontainers(disabledWithoutDocker = true)
class CompanySeedMigrationRerunTest {

    @Test
    void V2_기업_시드는_전체_버전_수와_무관하게_정확히_한_번만_적용된다() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();

            // 1차: target 을 V2 로 고정해 V1 → V2 만 적용. 뒤에 V3, V4… 가 생겨도 이 단계는 항상 2.
            Flyway toV2 = Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .target("2")
                    .load();
            int appliedToV2 = toV2.migrate().migrationsExecuted;
            assertThat(appliedToV2).isEqualTo(2); // V1, V2 (전체 버전 수와 무관)

            DataSource ds = toV2.getConfiguration().getDataSource();
            assertThat(countCompanies(ds)).isEqualTo(28);
            assertThat(countCompaniesWhere(ds, "verified = true")).isEqualTo(21);
            assertThat(countCompaniesWhere(ds, "verified = false")).isEqualTo(7);
            assertThat(countV2History(ds)).isEqualTo(1);

            // 2차: target 없이 전체 migrate(). 뒤 버전(V3…)만 올라가고 V2 는 재적용되지 않아야 한다.
            Flyway full = Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load();
            int appliedAfterV2 = full.migrate().migrationsExecuted;
            // 뒤 버전이 하나라도 있으면 그 수만큼, 없으면 0. 어느 경우든 "V2 재적용 없음" 이 핵심.
            assertThat(appliedAfterV2).isGreaterThanOrEqualTo(0);

            // V2 이력은 여전히 1건, 기업 수도 28 그대로(시드 중복 INSERT 없음).
            assertThat(countV2History(ds)).isEqualTo(1);
            assertThat(countCompanies(ds)).isEqualTo(28);

            // 3차: 같은 DB 에 다시 migrate(). 모든 버전이 이미 적용돼 0 건이어야 한다.
            int appliedAgain = full.migrate().migrationsExecuted;
            assertThat(appliedAgain).isZero();

            // 반복 실행 후에도 V2 이력 1건·기업 수 28 유지(멱등).
            assertThat(countV2History(ds)).isEqualTo(1);
            assertThat(countCompanies(ds)).isEqualTo(28);
        }
    }

    private int countCompanies(DataSource ds) throws Exception {
        return queryInt(ds, "select count(*) from company");
    }

    private int countCompaniesWhere(DataSource ds, String condition) throws Exception {
        return queryInt(ds, "select count(*) from company where " + condition);
    }

    private int countV2History(DataSource ds) throws Exception {
        return queryInt(ds, "select count(*) from flyway_schema_history where version = '2' and success = true");
    }

    private int queryInt(DataSource ds, String sql) throws Exception {
        try (Connection con = ds.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
