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
 * Flyway 를 <b>직접 두 번 실행</b>해 재적용(멱등) 동작을 확인합니다.
 *
 * <p>{@code @DataJpaTest}({@code CompanySeedMigrationTest})는 Spring 컨텍스트 캐시로
 * Flyway 가 JVM 당 한 번만 도는 것을 보지만, 여기서는 깨끗한 컨테이너에 Flyway API 로
 * V1 → V2 를 올린 뒤 <b>같은 DB 에 한 번 더 {@code migrate()}</b> 를 호출해
 * V2 가 다시 적용되지 않고(이력 1건 유지) 기업 수가 28 그대로인지 검증합니다.
 *
 * <p>이 테스트는 자체 컨테이너를 쓰므로 싱글턴({@code PostgresRepositoryTest})과 분리됩니다.
 * Docker 가 없으면 건너뜁니다({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@Testcontainers(disabledWithoutDocker = true)
class CompanySeedMigrationRerunTest {

    @Test
    void Flyway_를_두_번_실행해도_V2_는_한_번만_적용되고_기업_수가_28_그대로다() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();

            Flyway flyway = Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load();

            // 1차: V1 → V2 적용.
            int firstApplied = flyway.migrate().migrationsExecuted;
            assertThat(firstApplied).isEqualTo(2); // V1, V2

            DataSource ds = flyway.getConfiguration().getDataSource();
            assertThat(countCompanies(ds)).isEqualTo(28);
            assertThat(countCompaniesWhere(ds, "verified = true")).isEqualTo(21);
            assertThat(countCompaniesWhere(ds, "verified = false")).isEqualTo(7);
            assertThat(countV2History(ds)).isEqualTo(1);

            // 2차: 같은 DB 에 다시 migrate(). 이미 적용된 V1·V2 는 재실행되지 않아야 한다.
            int secondApplied = flyway.migrate().migrationsExecuted;
            assertThat(secondApplied).isZero();

            // 재실행 후에도 기업 수와 이력은 그대로(중복 INSERT 없음).
            assertThat(countCompanies(ds)).isEqualTo(28);
            assertThat(countV2History(ds)).isEqualTo(1);
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
