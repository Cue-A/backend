package com.cuea.domain.calendar.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * calendar_event V3 마이그레이션(Issue #130)을 Flyway 로 직접 실행해 검증합니다.
 *
 * <p>자체 컨테이너를 써 싱글턴({@code PostgresRepositoryTest})과 분리됩니다. 핵심은
 * <b>기존 데이터 보존과 백필 순서</b>입니다. V2 까지만 올린 뒤(구 스키마: company_name·job_role·
 * interview_method NOT NULL, title·time_specified 없음) 기존 행을 넣고, 그 다음 V3 를 올려:
 * <ul>
 *   <li>기존 행이 지워지지 않고 title 이 "회사명 직무 면접" 으로 백필된다</li>
 *   <li>기존 행의 time_specified 가 true 로 백필된다(구 데이터는 실제 시각 면접)</li>
 *   <li>company_name·job_role·interview_method NOT NULL 이 완화돼 title/시각만으로 insert 된다</li>
 *   <li>interview_method CHECK(ONLINE/OFFLINE)는 유지되고 NULL 은 통과한다</li>
 * </ul>
 * 빈 DB 에서 V1→V2→V3 전체가 성공하는 것은 {@link #빈_DB_에서_V1_V2_V3_전체가_성공한다} 가 봅니다.
 *
 * <p>Docker 가 없으면 건너뜁니다({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@Testcontainers(disabledWithoutDocker = true)
class CalendarEventMigrationTest {

    @Test
    void 빈_DB_에서_V1_V2_V3_전체가_성공한다() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            Flyway flyway = flyway(pg);

            int applied = flyway.migrate().migrationsExecuted;
            assertThat(applied).isEqualTo(3); // V1, V2, V3

            DataSource ds = flyway.getConfiguration().getDataSource();
            assertThat(queryInt(ds, "select count(*) from flyway_schema_history where version = '3' and success = true"))
                    .isEqualTo(1);

            // 최종 스키마: title·time_specified NOT NULL, 셋은 nullable.
            assertThat(isNullable(ds, "title")).isFalse();
            assertThat(isNullable(ds, "time_specified")).isFalse();
            assertThat(isNullable(ds, "company_name")).isTrue();
            assertThat(isNullable(ds, "job_role")).isTrue();
            assertThat(isNullable(ds, "interview_method")).isTrue();
        }
    }

    @Test
    void 기존_데이터는_보존되고_title_과_time_specified_가_백필된다() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();

            // 1) V2 까지만(구 스키마).
            Flyway toV2 = Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .target("2")
                    .load();
            assertThat(toV2.migrate().migrationsExecuted).isEqualTo(2);
            DataSource ds = toV2.getConfiguration().getDataSource();

            // 2) 구 스키마 기준으로 사용자와 기존 일정(실제 면접 일정) 1건 삽입.
            exec(ds, "insert into users (user_id, nickname, created_at) "
                    + "values ('u-old', '기존사용자', now())");
            exec(ds, """
                    insert into calendar_event
                        (user_id, company_name, job_role, interview_round, scheduled_at,
                         interview_method, location, memo, created_at, updated_at)
                    values
                        ('u-old', '네이버', '백엔드', '1차', timestamptz '2026-08-14 14:00:00+09',
                         'OFFLINE', '분당', '포폴 대비', now(), now())
                    """);

            // 3) V3 적용.
            Flyway toV3 = Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load();
            assertThat(toV3.migrate().migrationsExecuted).isEqualTo(1); // V3 만

            // 기존 행 보존 + 백필 확인.
            assertThat(queryInt(ds, "select count(*) from calendar_event")).isEqualTo(1);
            assertThat(queryString(ds, "select title from calendar_event where user_id = 'u-old'"))
                    .isEqualTo("네이버 백엔드 면접");
            assertThat(queryBool(ds, "select time_specified from calendar_event where user_id = 'u-old'"))
                    .isTrue();
            // 기존 값도 그대로 보존(완화는 값을 지우지 않음).
            assertThat(queryString(ds, "select company_name from calendar_event where user_id = 'u-old'"))
                    .isEqualTo("네이버");
            assertThat(queryString(ds, "select memo from calendar_event where user_id = 'u-old'"))
                    .isEqualTo("포폴 대비");

            // 4) 완화 확인: 새 API 방식(title/scheduled_at/time_specified 만, 셋은 null)으로 insert 성공.
            exec(ds, """
                    insert into calendar_event
                        (user_id, title, scheduled_at, time_specified, created_at, updated_at)
                    values
                        ('u-old', '카카오 서류 발표', timestamptz '2026-08-20 00:00:00+09', false, now(), now())
                    """);
            assertThat(queryInt(ds, "select count(*) from calendar_event where user_id = 'u-old'")).isEqualTo(2);

            // 5) interview_method CHECK 유지: 허용 외 값은 거부된다.
            assertThatThrownBy(() -> exec(ds, """
                    insert into calendar_event
                        (user_id, title, scheduled_at, time_specified, interview_method, created_at, updated_at)
                    values
                        ('u-old', '잘못된 방식', now(), true, 'PHONE', now(), now())
                    """)).isInstanceOf(SQLException.class);
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> pg) {
        return Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private boolean isNullable(DataSource ds, String column) throws SQLException {
        return "YES".equals(queryString(ds,
                "select is_nullable from information_schema.columns "
                        + "where table_name = 'calendar_event' and column_name = '" + column + "'"));
    }

    private void exec(DataSource ds, String sql) throws SQLException {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private int queryInt(DataSource ds, String sql) throws SQLException {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String queryString(DataSource ds, String sql) throws SQLException {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private boolean queryBool(DataSource ds, String sql) throws SQLException {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
