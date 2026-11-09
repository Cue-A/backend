package com.cuea.domain.growth.repository;

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
 * V7 배지 시드를 <b>신규 DB</b> 와 <b>재실행</b> 모두에서 검증합니다(Issue #131).
 *
 * <p>Flyway API 로 깨끗한 컨테이너에 전체 마이그레이션(V1~V7)을 올려 배지 정의 5종이
 * 들어가는지, 같은 DB 에 {@code migrate()} 를 한 번 더 호출해도 중복 적재되지 않는지
 * (ON CONFLICT (badge_code) DO NOTHING + Flyway 이력)를 봅니다. 또한 <b>기존 badge 정의가
 * 이미 있는 DB</b> 에서 V7 을 돌려도 기존 행을 덮어쓰거나 중복하지 않는지 확인합니다.
 *
 * <p>자체 컨테이너를 쓰므로 싱글턴({@code PostgresRepositoryTest})과 분리됩니다. Docker 가
 * 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
class BadgeSeedMigrationTest {

    @Test
    void 신규_DB_에_V7_로_배지_5종이_들어가고_재실행해도_중복되지_않는다() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load();

            flyway.migrate();
            DataSource ds = flyway.getConfiguration().getDataSource();

            assertThat(countBadges(ds)).isEqualTo(5);
            assertThat(badgeCodes(ds)).contains("FIRST_SESSION", "STREAK_3", "PRESSURE_CLEAR", "RISING", "TEN_SESSIONS");
            assertThat(countV7History(ds)).isEqualTo(1);

            // 재실행: 이미 적용된 V7 은 다시 돌지 않는다.
            int secondApplied = flyway.migrate().migrationsExecuted;
            assertThat(secondApplied).isZero();
            assertThat(countBadges(ds)).isEqualTo(5);
            assertThat(countV7History(ds)).isEqualTo(1);
        }
    }

    @Test
    void 기존_badge_정의가_있는_DB_에서도_V7_은_덮어쓰지_않고_중복하지_않는다() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    // V1·V2 까지만 먼저 올려 badge 테이블만 만든다(시드 전).
                    .target("2")
                    .load();
            flyway.migrate();
            DataSource ds = flyway.getConfiguration().getDataSource();

            // 사람이 이미 넣어 둔 FIRST_SESSION 정의(이름이 다름) — V7 이 이 행을 덮어쓰면 안 된다.
            try (Connection con = ds.getConnection(); Statement st = con.createStatement()) {
                st.executeUpdate("""
                        insert into badge (badge_code, badge_name, description, category, condition_type,
                                           condition_data, status, created_at, updated_at)
                        values ('FIRST_SESSION', '수동이름', '수동설명', 'X', 'Y', '{}'::jsonb, 'ACTIVE', now(), now())
                        """);
            }

            // 이제 V7 까지 올린다.
            Flyway full = Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load();
            full.migrate();

            // 기존 FIRST_SESSION 은 덮이지 않고(이름 그대로), 중복 생기지 않고, 나머지 4종만 추가돼 총 5종.
            assertThat(countBadges(ds)).isEqualTo(5);
            assertThat(badgeName(ds, "FIRST_SESSION")).isEqualTo("수동이름");
            assertThat(badgeCodes(ds)).contains("STREAK_3", "PRESSURE_CLEAR", "RISING", "TEN_SESSIONS");
        }
    }

    private int countBadges(DataSource ds) throws Exception {
        return queryInt(ds, "select count(*) from badge");
    }

    private int countV7History(DataSource ds) throws Exception {
        return queryInt(ds, "select count(*) from flyway_schema_history where version = '7' and success = true");
    }

    private java.util.List<String> badgeCodes(DataSource ds) throws Exception {
        java.util.List<String> codes = new java.util.ArrayList<>();
        try (Connection con = ds.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("select badge_code from badge")) {
            while (rs.next()) {
                codes.add(rs.getString(1));
            }
        }
        return codes;
    }

    private String badgeName(DataSource ds, String code) throws Exception {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("select badge_name from badge where badge_code = '" + code + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private int queryInt(DataSource ds, String sql) throws Exception {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
