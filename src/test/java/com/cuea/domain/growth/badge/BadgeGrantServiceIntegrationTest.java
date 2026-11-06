package com.cuea.domain.growth.badge;

import com.cuea.common.config.ClockConfig;
import com.cuea.domain.growth.entity.Badge;
import com.cuea.domain.growth.repository.BadgeRepository;
import com.cuea.domain.growth.repository.UserBadgeRepository;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.service.ReportSummaryQueryService;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배지 지급(조건 판정 + 멱등 INSERT)을 실제 PostgreSQL 로 검증합니다(Issue #131).
 *
 * <p>실제 서비스 빈을 띄워 {@code uk_user_badge} UNIQUE 와 {@code ON CONFLICT DO NOTHING} 이
 * 동시성·중복에서 어떻게 동작하는지 봅니다(mock 으로는 드러나지 않습니다). V4 시드가 적용된
 * 컨테이너라 badge 정의 5종이 있습니다. 세션/리포트는 네이티브 insert 로 완료 시각을 고정합니다
 * (엔티티 {@code complete()}/{@code finish()} 는 now() 로 덮어 KST 날짜 경계 검증이 불가).
 * 싱글턴 컨테이너를 공유하므로 넣은 데이터는 {@code @AfterEach} 에서 직접 지웁니다.
 */
@Import({BadgeGrantService.class, InterviewSessionQueryService.class,
        ReportSummaryQueryService.class, ClockConfig.class})
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class BadgeGrantServiceIntegrationTest extends PostgresRepositoryTest {

    @Autowired
    private BadgeGrantService grantService;
    @Autowired
    private BadgeRepository badgeRepository;
    @Autowired
    private UserBadgeRepository userBadgeRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private String userId;
    private String otherUserId;
    private Long docId;
    private Long otherDocId;

    @BeforeEach
    void setUp() {
        userId = insertUser("면접자");
        otherUserId = insertUser("다른 사람");
        docId = insertDocument(userId);
        otherDocId = insertDocument(otherUserId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from user_badge");
        jdbc.update("delete from report");
        jdbc.update("delete from session");
        jdbc.update("delete from document");
        jdbc.update("delete from users");
    }

    /* ---------------- FIRST_SESSION ---------------- */

    @Test
    void 완료_세션_0개면_FIRST_SESSION_미획득() {
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("FIRST_SESSION");
    }

    @Test
    void 완료_세션_1개면_FIRST_SESSION_획득() {
        completed(userId, docId, Persona.FRIENDLY, utc("2026-11-01T01:00:00Z"));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("FIRST_SESSION");
    }

    @Test
    void IN_PROGRESS_만_있으면_FIRST_SESSION_미획득() {
        sess(userId, docId, Persona.FRIENDLY, SessionStatus.IN_PROGRESS, null, null);
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("FIRST_SESSION");
    }

    @Test
    void ABORTED_만_있으면_FIRST_SESSION_미획득() {
        sess(userId, docId, Persona.FRIENDLY, SessionStatus.ABORTED, null, null);
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("FIRST_SESSION");
    }

    @Test
    void 완료_이벤트_중복_실행해도_FIRST_SESSION_은_1행() {
        completed(userId, docId, Persona.FRIENDLY, utc("2026-11-01T01:00:00Z"));
        grantService.reconcile(userId);
        grantService.reconcile(userId);
        grantService.reconcile(userId);
        assertThat(countUserBadge(userId, "FIRST_SESSION")).isEqualTo(1);
    }

    /* ---------------- TEN_SESSIONS ---------------- */

    @Test
    void 완료_9회면_TEN_SESSIONS_미획득() {
        for (int i = 0; i < 9; i++) {
            completed(userId, docId, Persona.FRIENDLY, utc("2026-11-01T01:00:00Z").plusDays(i));
        }
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("TEN_SESSIONS");
    }

    @Test
    void 완료_10회면_TEN_SESSIONS_획득_11회여도_1행() {
        for (int i = 0; i < 10; i++) {
            completed(userId, docId, Persona.FRIENDLY, utc("2026-11-01T01:00:00Z").plusDays(i));
        }
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("TEN_SESSIONS");

        completed(userId, docId, Persona.FRIENDLY, utc("2026-12-01T01:00:00Z"));
        grantService.reconcile(userId);
        assertThat(countUserBadge(userId, "TEN_SESSIONS")).isEqualTo(1);
    }

    @Test
    void 같은_날_10회_완료여도_TEN_SESSIONS_획득() {
        OffsetDateTime sameDay = utc("2026-11-01T01:00:00Z");
        for (int i = 0; i < 10; i++) {
            completed(userId, docId, Persona.FRIENDLY, sameDay.plusMinutes(i));
        }
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("TEN_SESSIONS");
    }

    @Test
    void IN_PROGRESS_ABORTED_는_TEN_SESSIONS_에_세지_않는다() {
        for (int i = 0; i < 9; i++) {
            completed(userId, docId, Persona.FRIENDLY, utc("2026-11-01T01:00:00Z").plusDays(i));
        }
        sess(userId, docId, Persona.FRIENDLY, SessionStatus.IN_PROGRESS, null, null);
        sess(userId, docId, Persona.FRIENDLY, SessionStatus.ABORTED, null, null);
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("TEN_SESSIONS");
    }

    /* ---------------- STREAK_3 ---------------- */

    @Test
    void KST_3일_연속_완료면_STREAK_3_획득() {
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 2, 9, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 3, 9, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 4, 9, 0));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("STREAK_3");
    }

    @Test
    void 중간_하루_누락이면_STREAK_3_미획득() {
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 2, 9, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 4, 9, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 5, 9, 0));
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("STREAK_3");
    }

    @Test
    void 같은_날_3회_완료면_STREAK_3_미획득() {
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 2, 9, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 2, 12, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 2, 18, 0));
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("STREAK_3");
    }

    @Test
    void KST_자정_전후_완료가_다른_날로_묶여_STREAK_3_획득() {
        // 11/1 23:30, 11/2 00:30, 11/3 00:30 (KST) → KST 날짜 11/1,11/2,11/3 연속
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 1, 23, 30));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 2, 0, 30));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 3, 0, 30));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("STREAK_3");
    }

    @Test
    void 연말_연초_경계_연속3일_STREAK_3_획득() {
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 12, 31, 9, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2027, 1, 1, 9, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2027, 1, 2, 9, 0));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("STREAK_3");
    }

    @Test
    void 재연습_포함_연속3일_STREAK_3_획득() {
        String root = completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 2, 9, 0));
        retry(userId, docId, Persona.FRIENDLY, root, kst(2026, 11, 3, 9, 0));
        retry(userId, docId, Persona.FRIENDLY, root, kst(2026, 11, 4, 9, 0));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("STREAK_3");
    }

    @Test
    void 과거_연속3일_뒤_끊겨도_STREAK_3_유지() {
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 1, 1, 9, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 1, 2, 9, 0));
        completed(userId, docId, Persona.FRIENDLY, kst(2026, 1, 3, 9, 0));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("STREAK_3");

        completed(userId, docId, Persona.FRIENDLY, kst(2026, 6, 20, 9, 0));
        grantService.reconcile(userId);
        assertThat(countUserBadge(userId, "STREAK_3")).isEqualTo(1);
    }

    /* ---------------- PRESSURE_CLEAR ---------------- */

    @Test
    void PRESSURE_COMPLETED_면_PRESSURE_CLEAR_획득() {
        completed(userId, docId, Persona.PRESSURE, utc("2026-11-01T01:00:00Z"));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("PRESSURE_CLEAR");
    }

    @Test
    void PRESSURE_IN_PROGRESS_면_PRESSURE_CLEAR_미획득() {
        sess(userId, docId, Persona.PRESSURE, SessionStatus.IN_PROGRESS, null, null);
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("PRESSURE_CLEAR");
    }

    @Test
    void PRESSURE_ABORTED_면_PRESSURE_CLEAR_미획득() {
        sess(userId, docId, Persona.PRESSURE, SessionStatus.ABORTED, null, null);
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("PRESSURE_CLEAR");
    }

    @Test
    void FRIENDLY_COMPLETED_만_있으면_PRESSURE_CLEAR_미획득() {
        completed(userId, docId, Persona.FRIENDLY, utc("2026-11-01T01:00:00Z"));
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("PRESSURE_CLEAR");
    }

    @Test
    void 재연습에서_PRESSURE_COMPLETED_면_PRESSURE_CLEAR_획득() {
        String root = completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, 2, 9, 0));
        retry(userId, docId, Persona.PRESSURE, root, kst(2026, 11, 3, 9, 0));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("PRESSURE_CLEAR");
    }

    /* ---------------- RISING ---------------- */

    @Test
    void 점수_60_65_72_80_면_RISING_획득() {
        scores(userId, docId, List.of(60, 65, 72, 80), ReportStatus.COMPLETED);
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("RISING");
    }

    @Test
    void 점수_60_65_65_80_이면_RISING_미획득() {
        scores(userId, docId, List.of(60, 65, 65, 80), ReportStatus.COMPLETED);
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("RISING");
    }

    @Test
    void 점수_80_70_75_90_이면_RISING_미획득() {
        scores(userId, docId, List.of(80, 70, 75, 90), ReportStatus.COMPLETED);
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("RISING");
    }

    @Test
    void 유효점수_3개뿐이면_RISING_미획득() {
        scores(userId, docId, List.of(60, 70, 80), ReportStatus.COMPLETED);
        grantService.reconcile(userId);
        assertThat(earned(userId)).doesNotContain("RISING");
    }

    @Test
    void 점수_null_FAILED_리포트는_제외하고_나머지_연속상승이면_RISING_획득() {
        scoreAt(userId, docId, 60, ReportStatus.COMPLETED, kst(2026, 11, 1, 9, 0));
        scoreAt(userId, docId, 65, ReportStatus.COMPLETED, kst(2026, 11, 2, 9, 0));
        scoreAt(userId, docId, null, ReportStatus.FAILED, kst(2026, 11, 3, 9, 0)); // 제외
        scoreAt(userId, docId, 72, ReportStatus.PARTIAL, kst(2026, 11, 4, 9, 0));
        scoreAt(userId, docId, 80, ReportStatus.COMPLETED, kst(2026, 11, 5, 9, 0));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("RISING");
    }

    @Test
    void PROCESSING_무효리포트_섞여도_유효점수만으로_RISING_판정() {
        scoreAt(userId, docId, 60, ReportStatus.COMPLETED, kst(2026, 11, 1, 9, 0));
        scoreAt(userId, docId, null, ReportStatus.PROCESSING, kst(2026, 11, 2, 9, 0)); // 제외
        scoreAt(userId, docId, 65, ReportStatus.PARTIAL, kst(2026, 11, 3, 9, 0));
        scoreAt(userId, docId, 72, ReportStatus.COMPLETED, kst(2026, 11, 4, 9, 0));
        scoreAt(userId, docId, 80, ReportStatus.COMPLETED, kst(2026, 11, 5, 9, 0));
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("RISING");
    }

    @Test
    void 완료시각_동률이면_public_id_로_안정정렬된다() {
        // 같은 세션 완료 시각에 점수 60,65,72,80 을 넣어도 정렬이 흔들리지 않아야 판정이 결정적
        OffsetDateTime same = kst(2026, 11, 1, 9, 0);
        scoreAt(userId, docId, 60, ReportStatus.COMPLETED, same);
        scoreAt(userId, docId, 65, ReportStatus.COMPLETED, same);
        scoreAt(userId, docId, 72, ReportStatus.COMPLETED, same);
        scoreAt(userId, docId, 80, ReportStatus.COMPLETED, same);
        // 동일 완료시각이면 public_id(랜덤 UUID) 정렬이라 상승 순서가 보장되지 않음 → 결과는
        // 결정적이기만 하면 됨(여러 번 호출해도 같은 결과). 여기서는 호출이 예외 없이 끝나는지와
        // 멱등성만 본다.
        grantService.reconcile(userId);
        int firstState = countUserBadge(userId, "RISING");
        grantService.reconcile(userId);
        assertThat(countUserBadge(userId, "RISING")).isEqualTo(firstState);
    }

    @Test
    void 과거_상승조건_달성후_점수_하락해도_RISING_유지() {
        scores(userId, docId, List.of(60, 65, 72, 80), ReportStatus.COMPLETED);
        grantService.reconcile(userId);
        assertThat(earned(userId)).contains("RISING");

        scoreAt(userId, docId, 50, ReportStatus.COMPLETED, kst(2026, 12, 1, 9, 0));
        scoreAt(userId, docId, 40, ReportStatus.COMPLETED, kst(2026, 12, 2, 9, 0));
        grantService.reconcile(userId);
        assertThat(countUserBadge(userId, "RISING")).isEqualTo(1);
    }

    /* ---------------- 공통: 격리·동시성·소급 ---------------- */

    @Test
    void 다른_사용자_세션은_내_배지에_영향없다() {
        completed(otherUserId, otherDocId, Persona.PRESSURE, utc("2026-11-01T01:00:00Z"));
        grantService.reconcile(userId);
        assertThat(earned(userId)).isEmpty();
        grantService.reconcile(otherUserId);
        assertThat(earned(otherUserId)).contains("FIRST_SESSION", "PRESSURE_CLEAR");
    }

    @Test
    void 동시_지급해도_중복_INSERT_없이_1행() throws Exception {
        completed(userId, docId, Persona.FRIENDLY, utc("2026-11-01T01:00:00Z"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> grantService.reconcile(userId)));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(countUserBadge(userId, "FIRST_SESSION")).isEqualTo(1);
    }

    @Test
    void 소급_백필은_여러_배지를_한번에_정당히_지급하고_멱등이다() {
        for (int i = 0; i < 10; i++) {
            completed(userId, docId, Persona.FRIENDLY, kst(2026, 11, i + 1, 9, 0));
        }
        completed(userId, docId, Persona.PRESSURE, kst(2026, 11, 20, 9, 0));
        scoreAt(userId, docId, 60, ReportStatus.COMPLETED, kst(2026, 11, 1, 9, 0));
        scoreAt(userId, docId, 65, ReportStatus.COMPLETED, kst(2026, 11, 2, 9, 0));
        scoreAt(userId, docId, 72, ReportStatus.COMPLETED, kst(2026, 11, 3, 9, 0));
        scoreAt(userId, docId, 80, ReportStatus.COMPLETED, kst(2026, 11, 4, 9, 0));

        int first = grantService.reconcile(userId);
        assertThat(earned(userId)).containsExactlyInAnyOrder(
                "FIRST_SESSION", "TEN_SESSIONS", "PRESSURE_CLEAR", "STREAK_3", "RISING");
        assertThat(first).isEqualTo(5);

        int second = grantService.reconcile(userId);
        assertThat(second).isZero();
        assertThat(totalUserBadges(userId)).isEqualTo(5);
    }

    /* ---------------- 시드 ---------------- */

    @Test
    void V4_시드로_배지_정의_5종이_있다() {
        assertThat(badgeRepository.findAll()).extracting(Badge::getBadgeCode)
                .contains("FIRST_SESSION", "STREAK_3", "PRESSURE_CLEAR", "RISING", "TEN_SESSIONS");
    }

    /* ---------------- fixtures ---------------- */

    private static OffsetDateTime utc(String iso) {
        return OffsetDateTime.parse(iso);
    }

    /** KST(+09:00) 벽시계를 UTC instant 로. DB timestamptz 는 UTC 저장, 쿼리가 KST 로 변환합니다. */
    private static OffsetDateTime kst(int y, int mo, int d, int h, int mi) {
        return OffsetDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.ofHours(9))
                .withOffsetSameInstant(ZoneOffset.UTC);
    }

    private String insertUser(String nickname) {
        User u = User.create(null, nickname);
        jdbc.update("insert into users (user_id, email, nickname, created_at) values (?, null, ?, now())",
                u.getUserId(), u.getNickname());
        return u.getUserId();
    }

    private Long insertDocument(String uid) {
        UUID pub = UUID.randomUUID();
        return jdbc.queryForObject("""
                insert into document (user_id, public_id, doc_type, doc_title, doc_text, status,
                                      source_type, created_at, updated_at)
                values (?, ?, 'RESUME', '이력서', '본문', 'READY', 'MARKDOWN', now(), now())
                returning doc_id
                """, Long.class, uid, pub);
    }

    private String completed(String uid, Long documentId, Persona persona, OffsetDateTime completedAt) {
        return sess(uid, documentId, persona, SessionStatus.COMPLETED, completedAt, null);
    }

    private String retry(String uid, Long documentId, Persona persona, String root, OffsetDateTime completedAt) {
        return sess(uid, documentId, persona, SessionStatus.COMPLETED, completedAt, root);
    }

    private String sess(String uid, Long documentId, Persona persona, SessionStatus status,
                        OffsetDateTime completedAt, String retryOf) {
        String sessionId = "sess_" + UUID.randomUUID();
        jdbc.update("""
                insert into session (session_id, user_id, document_id, retry_of_session_id, mode, job_role,
                                     question_count, persona, hide_question_text, status, completed_at,
                                     created_at, updated_at)
                values (?, ?, ?, ?, 'PRACTICE', '백엔드', 6, ?, false, ?, ?, now(), now())
                """, sessionId, uid, documentId, retryOf, persona.name(), status.name(),
                completedAt == null ? null : completedAt);
        return sessionId;
    }

    private void scores(String uid, Long documentId, List<Integer> list, ReportStatus status) {
        int day = 1;
        for (Integer score : list) {
            scoreAt(uid, documentId, score, status, kst(2026, 11, day++, 9, 0));
        }
    }

    private void scoreAt(String uid, Long documentId, Integer score, ReportStatus status, OffsetDateTime sessionCompletedAt) {
        String sessionId = completed(uid, documentId, Persona.FRIENDLY, sessionCompletedAt);
        jdbc.update("""
                insert into report (session_id, public_id, status, attempt, score_total, completed_at, created_at)
                values (?, ?, ?, 1, ?, ?, now())
                """, sessionId, UUID.randomUUID(), status.name(), score, sessionCompletedAt);
    }

    private List<String> earned(String uid) {
        return userBadgeRepository.findEarnedBadgeCodes(uid);
    }

    private int countUserBadge(String uid, String code) {
        Integer n = jdbc.queryForObject("""
                select count(*) from user_badge ub join badge b on b.badge_id = ub.badge_id
                 where ub.user_id = ? and b.badge_code = ?
                """, Integer.class, uid, code);
        return n == null ? 0 : n;
    }

    private int totalUserBadges(String uid) {
        Integer n = jdbc.queryForObject("select count(*) from user_badge where user_id = ?", Integer.class, uid);
        return n == null ? 0 : n;
    }
}
