package com.cuea.domain.growth.service;

import com.cuea.domain.growth.dto.response.WeeklyGoalResponse;
import com.cuea.domain.home.service.KoreaTime;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주간 목표 upsert·실적 집계·동시성을 실제 PostgreSQL 로 봅니다. mock 서비스 테스트로는
 * {@code SELECT FOR UPDATE}·실제 트랜잭션 커밋·KST 날짜 경계가 검증되지 않습니다.
 *
 * <p>시계는 2026-11-06 금요일 12:00 KST 로 고정합니다 → "이번 주" = 월 2026-11-02 ~ 일 2026-11-08.
 * 실적 집계 구간은 2026-11-02 00:00 KST(=11-01T15:00Z) ~ 2026-11-09 00:00 KST(=11-08T15:00Z).
 *
 * <p>테스트 메서드는 {@code NOT_SUPPORTED} 로 트랜잭션 밖에서 돌려 서비스의 upsert 가 진짜 물리
 * 트랜잭션을 열고 커밋하게 합니다({@code CompanyInterestServiceIntegrationTest} 와 같은 방식).
 * 싱글턴 컨테이너를 공유하므로 넣은 데이터는 {@code @AfterEach} 에서 직접 지웁니다.
 */
@Import({WeeklyGoalService.class, InterviewSessionQueryService.class,
        WeeklyGoalServiceIntegrationTest.FixedClockConfig.class})
class WeeklyGoalServiceIntegrationTest extends PostgresRepositoryTest {

    /** 2026-11-06(금) 12:00 KST. 이번 주 = 2026-11-02(월) ~ 2026-11-08(일). */
    static final Instant NOW = LocalDate.of(2026, 11, 6).atTime(12, 0).atZone(KoreaTime.ZONE).toInstant();
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 8);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private WeeklyGoalService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private String userId;
    private String otherUserId;
    private Long documentId;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            userId = insertUser("목표사용자");
            otherUserId = insertUser("다른사용자");
            documentId = insertDocument(userId);
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from session where user_id in (?, ?)", userId, otherUserId);
        jdbc.update("delete from goal where user_id in (?, ?)", userId, otherUserId);
        jdbc.update("delete from document where user_id = ?", userId);
        jdbc.update("delete from users where user_id in (?, ?)", userId, otherUserId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 최초_설정은_WEEKLY_SESSION_COUNT_행_하나를_이번_주_월_일로_만든다() {
        WeeklyGoalResponse response = service.upsertThisWeek(userId, 3);

        assertThat(response.targetValue()).isEqualTo(3);
        assertThat(response.doneCount()).isZero();
        assertThat(countGoals()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select goal_type from goal where user_id = ?", String.class, userId)).isEqualTo("WEEKLY");
        assertThat(jdbc.queryForObject(
                "select target_metric from goal where user_id = ?", String.class, userId)).isEqualTo("SESSION_COUNT");
        assertThat(jdbc.queryForObject(
                "select status from goal where user_id = ?", String.class, userId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "select start_date from goal where user_id = ?", LocalDate.class, userId)).isEqualTo(MONDAY);
        assertThat(jdbc.queryForObject(
                "select end_date from goal where user_id = ?", LocalDate.class, userId)).isEqualTo(SUNDAY);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_주_수정은_행을_늘리지_않고_목표치만_바꾼다() {
        service.upsertThisWeek(userId, 3);
        WeeklyGoalResponse updated = service.upsertThisWeek(userId, 6);

        assertThat(updated.targetValue()).isEqualTo(6);
        assertThat(countGoals()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select target_value from goal where user_id = ?", Integer.class, userId)).isEqualTo(6);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 동일_값_재요청도_멱등이고_행은_하나다() {
        service.upsertThisWeek(userId, 4);
        service.upsertThisWeek(userId, 4);

        assertThat(countGoals()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select target_value from goal where user_id = ?", Integer.class, userId)).isEqualTo(4);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 동시에_최초_설정을_두_번_보내도_같은_주_목표가_한_행만_생긴다() throws InterruptedException {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            final int value = (i % 7) + 1;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.upsertThisWeek(userId, value);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(error.get()).isNull();
        assertThat(countGoals()).isEqualTo(1); // 중복 행 없음(핵심)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 다음_주_목표는_새_행으로_생기고_이번_주와_격리된다() {
        service.upsertThisWeek(userId, 3); // 이번 주(11-02~08)

        // 다음 주 월요일(11-09) 행을 직접 심어 "주별 저장" 격리를 확인한다(시계는 이번 주 고정).
        insertGoal(userId, 5, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15));

        assertThat(countGoals()).isEqualTo(2);
        // 서비스는 여전히 이번 주 행(3)만 조회한다.
        assertThat(service.getThisWeek(userId).targetValue()).isEqualTo(3);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 다른_사용자의_목표와_격리된다() {
        service.upsertThisWeek(userId, 3);
        insertGoal(otherUserId, 7, MONDAY, SUNDAY);

        assertThat(service.getThisWeek(userId).targetValue()).isEqualTo(3);
        assertThat(service.getThisWeek(otherUserId).targetValue()).isEqualTo(7);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 미설정이면_null_이다() {
        assertThat(service.getThisWeek(userId)).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 이번_주_완료_세션을_재연습_포함_각_1회로_세고_진행중_중단은_뺀다() {
        // 이번 주(KST) 완료 4건: 같은 날 2건 + 다른 날(일요일 밤) 1건 + 재연습 1건.
        // 재연습 세션은 FK(fk_session_retry_of_session_id) 때문에 실재하는 원본 session_id 를 가리켜야 한다.
        // 원본(11-02 09:00)도 COMPLETED·이번 주라 그 자체로 1건이고, 재연습도 별도 1건이다.
        String original = insertSession(userId, SessionStatus.COMPLETED, kst(2026, 11, 2, 9, 0), null);
        insertSession(userId, SessionStatus.COMPLETED, kst(2026, 11, 2, 20, 0), null);   // 같은 날 또
        insertSession(userId, SessionStatus.COMPLETED, kst(2026, 11, 8, 23, 30), null);  // 일요일 밤
        insertSession(userId, SessionStatus.COMPLETED, kst(2026, 11, 5, 10, 0), original); // 재연습(원본 참조) 포함
        // 제외돼야 하는 것들
        insertSession(userId, SessionStatus.IN_PROGRESS, kst(2026, 11, 3, 10, 0), null);
        insertSession(userId, SessionStatus.ABORTED, kst(2026, 11, 3, 11, 0), null);
        // 지난 주(일요일 11-01 23:59 KST)·다음 주(월요일 11-09 00:00 KST) 경계 밖
        insertSession(userId, SessionStatus.COMPLETED, kst(2026, 11, 1, 23, 59), null);
        insertSession(userId, SessionStatus.COMPLETED, kst(2026, 11, 9, 0, 0), null);
        // 다른 사용자 완료는 섞이지 않는다
        insertSession(otherUserId, SessionStatus.COMPLETED, kst(2026, 11, 3, 10, 0), null);

        WeeklyGoalResponse response = service.upsertThisWeek(userId, 5);

        // 원본 + 같은 날 1건 + 일요일 밤 1건 + 재연습 1건 = 4
        assertThat(response.doneCount()).isEqualTo(4);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void KST_월요일_자정은_이번_주에_들고_직전_순간은_지난_주다() {
        // 월요일 00:00 KST(포함) vs 그 1분 전(지난 주)
        insertSession(userId, SessionStatus.COMPLETED, kst(2026, 11, 2, 0, 0), null);  // 포함
        insertSession(userId, SessionStatus.COMPLETED, kst(2026, 11, 1, 23, 59), null);// 제외

        assertThat(service.upsertThisWeek(userId, 3).doneCount()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 범위_밖_목표는_INVALID_REQUEST_이고_행이_생기지_않는다() {
        assertThatThrownBy(() -> service.upsertThisWeek(userId, 0))
                .hasMessageContaining("1~7");
        assertThatThrownBy(() -> service.upsertThisWeek(userId, 8))
                .hasMessageContaining("1~7");

        assertThat(countGoals()).isZero();
    }

    /* ------------------------------------------------------------------ 헬퍼 */

    private long countGoals() {
        Long n = jdbc.queryForObject(
                "select count(*) from goal where user_id = ? and goal_type = 'WEEKLY' and target_metric = 'SESSION_COUNT'",
                Long.class, userId);
        return n == null ? 0 : n;
    }

    private OffsetDateTime kst(int y, int mo, int d, int h, int mi) {
        return LocalDate.of(y, mo, d).atTime(h, mi).atZone(KoreaTime.ZONE).toOffsetDateTime();
    }

    private String insertUser(String nickname) {
        String id = User.create(null, nickname).getUserId();
        jdbc.update("insert into users (user_id, nickname, created_at) values (?, ?, now())", id, nickname);
        return id;
    }

    private Long insertDocument(String owner) {
        jdbc.update("""
                insert into document (public_id, user_id, doc_type, source_type, status, doc_title, doc_text, object_key, created_at, updated_at)
                values (?, ?, 'RESUME', 'MARKDOWN', 'READY', '이력서', '본문', 'docs/r.txt', now(), now())
                """, UUID.randomUUID(), owner);
        return jdbc.queryForObject("select doc_id from document where user_id = ?", Long.class, owner);
    }

    private String insertSession(String owner, SessionStatus status, OffsetDateTime completedAt, String retryOf) {
        String sessionId = "sess_" + UUID.randomUUID();
        jdbc.update("""
                insert into session (session_id, user_id, document_id, mode, job_role, question_count,
                                     persona, hide_question_text, status, completed_at, retry_of_session_id,
                                     created_at, updated_at)
                values (?, ?, ?, 'PRACTICE', '백엔드', 6, 'FRIENDLY', false, ?, ?, ?, now(), now())
                """,
                sessionId, owner, documentId, status.name(),
                completedAt == null ? null : completedAt, retryOf);
        return sessionId;
    }

    private void insertGoal(String owner, int targetValue, LocalDate start, LocalDate end) {
        jdbc.update("""
                insert into goal (user_id, goal_type, target_metric, target_value, start_date, end_date, status, created_at, updated_at)
                values (?, 'WEEKLY', 'SESSION_COUNT', ?, ?, ?, 'ACTIVE', now(), now())
                """, owner, targetValue, start, end);
    }
}
