package com.cuea.domain.growth.badge;

import com.cuea.common.config.AsyncConfig;
import com.cuea.common.config.ClockConfig;
import com.cuea.domain.growth.repository.BadgeRepository;
import com.cuea.domain.growth.repository.UserBadgeRepository;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.interview.service.InterviewSessionWriter;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.service.ReportSummaryQueryService;
import com.cuea.domain.report.service.ReportWriter;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 배지 자동 지급의 <b>전체 비동기 흐름</b>을 실제 PostgreSQL 로 검증합니다(Issue #131).
 *
 * <p>단위 테스트({@code InterviewSessionWriterTest}·{@code ReportWriterTest})는 "이벤트가
 * 발행되는가"만 봅니다. 여기서는 그다음을 끝까지 확인합니다:
 * <pre>
 *   완료 트랜잭션 커밋 → {@code @TransactionalEventListener(AFTER_COMMIT)} 발화
 *   → {@code @Async(BADGE_EXECUTOR)} 별도 스레드 → BadgeGrantService 지급 → user_badge INSERT
 * </pre>
 *
 * <p><b>왜 실제 커밋이 필요한가:</b> {@code AFTER_COMMIT} 리스너는 트랜잭션이 <b>커밋돼야</b>
 * 발화합니다. 그래서 테스트 자체에 트랜잭션을 걸지 않고({@code NOT_SUPPORTED}) 세션 행을 미리
 * 넣은 뒤, {@link InterviewSessionWriter#completeSession}·{@link ReportWriter#finish} 가 각자의
 * {@code @Transactional} 로 커밋하도록 합니다. 지급은 별도 스레드에서 비동기로 일어나므로
 * {@code Thread.sleep} 대신 {@code Awaitility} 로 user_badge 행이 나타날 때까지 폴링합니다
 * (결정적). 실제 {@code AsyncConfig}(가상 스레드 BADGE_EXECUTOR)와 {@code BadgeEventListener}
 * 를 그대로 띄워, @Async·@TransactionalEventListener 프록시 배선이 운영과 동일하게 동작하는지 봅니다.
 */
@Import({BadgeEventListener.class, BadgeGrantService.class,
        InterviewSessionWriter.class, ReportWriter.class,
        InterviewSessionQueryService.class, ReportSummaryQueryService.class,
        ClockConfig.class, AsyncConfig.class,
        BadgeAutoGrantEventFlowIntegrationTest.TestBeans.class})
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class BadgeAutoGrantEventFlowIntegrationTest extends PostgresRepositoryTest {

    /** @DataJpaTest 슬라이스에는 없는 빈을 보충합니다(ReportWriter 가 ObjectMapper 를 씁니다). */
    @TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private InterviewSessionWriter sessionWriter;
    @Autowired
    private ReportWriter reportWriter;
    @Autowired
    private BadgeRepository badgeRepository;
    @Autowired
    private UserBadgeRepository userBadgeRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private String userId;
    private Long docId;

    @BeforeEach
    void setUp() {
        userId = insertUser("면접자");
        docId = insertDocument(userId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from user_badge");
        jdbc.update("delete from report");
        jdbc.update("delete from session");
        jdbc.update("delete from document");
        jdbc.update("delete from users");
    }

    @Test
    void 세션_완료가_커밋되면_AFTER_COMMIT_비동기_리스너가_FIRST_SESSION_을_지급한다() {
        // IN_PROGRESS 세션을 미리 넣는다(완료 트랜잭션이 이 행을 보도록 선커밋).
        String sessionId = insertInProgressSession(userId, docId, Persona.FRIENDLY);

        // 실제 완료 경로: completeSession 이 자체 @Transactional 로 커밋하고 SessionCompletedEvent 발행.
        boolean transitioned = sessionWriter.completeSession(sessionId);
        assertThat(transitioned).isTrue();

        // 커밋 뒤 AFTER_COMMIT @Async 리스너가 별도 스레드에서 지급한다. 폴링으로 결정적으로 대기.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(earned(userId)).contains("FIRST_SESSION"));
        // 멱등: 비동기 지급이 한 번만, 1행만 남는다.
        assertThat(countUserBadge(userId, "FIRST_SESSION")).isEqualTo(1);
    }

    @Test
    void 압박_세션_완료가_커밋되면_비동기로_PRESSURE_CLEAR_도_함께_지급된다() {
        String sessionId = insertInProgressSession(userId, docId, Persona.PRESSURE);

        assertThat(sessionWriter.completeSession(sessionId)).isTrue();

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(earned(userId)).contains("FIRST_SESSION", "PRESSURE_CLEAR"));
    }

    @Test
    void 리포트_점수_확정이_커밋되면_AFTER_COMMIT_비동기_리스너가_RISING_을_지급한다() {
        // 유효 점수 4개가 연속 상승(60<65<72<80)하도록 완료 세션 + 리포트 PROCESSING 행을 만든다.
        long[] reportIds = new long[4];
        int[] scores = {60, 65, 72, 80};
        for (int i = 0; i < 4; i++) {
            String sessionId = insertCompletedSession(userId, docId, Persona.FRIENDLY,
                    OffsetDateTime.parse("2026-11-0" + (i + 1) + "T01:00:00Z"));
            reportIds[i] = insertProcessingReport(sessionId);
        }

        // 마지막 리포트 점수를 확정(finish) → ReportScoredEvent 발행 → 커밋 → 비동기 RISING 평가.
        // 앞 3개도 finish 해 유효 점수 수열을 완성한다(각 finish 가 개별 커밋·이벤트).
        for (int i = 0; i < 4; i++) {
            reportWriter.finish(reportIds[i], new com.cuea.domain.report.service.ReportResult(
                    ReportStatus.COMPLETED, scores[i], scores[i], scores[i], scores[i],
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
        }

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(earned(userId)).contains("RISING"));
        assertThat(countUserBadge(userId, "RISING")).isEqualTo(1);
    }

    @Test
    void ABORTED_세션에_늦게_도착한_완료는_이벤트도_지급도_없다() {
        // 이미 ABORTED 인 세션: completeSession 이 전이하지 않아 이벤트 미발행 → 비동기 지급 없음.
        String sessionId = insertSession(userId, docId, Persona.FRIENDLY, SessionStatus.ABORTED, null);

        assertThat(sessionWriter.completeSession(sessionId)).isFalse();

        // 지급이 일어나지 않아야 한다. 비동기라 "안 일어남"을 단정하려면 잠깐 안정화 후 확인.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(earned(userId)).isEmpty());
    }

    /* ---------------- fixtures ---------------- */

    private String insertInProgressSession(String uid, Long documentId, Persona persona) {
        return insertSession(uid, documentId, persona, SessionStatus.IN_PROGRESS, null);
    }

    private String insertCompletedSession(String uid, Long documentId, Persona persona, OffsetDateTime completedAt) {
        return insertSession(uid, documentId, persona, SessionStatus.COMPLETED, completedAt);
    }

    private String insertSession(String uid, Long documentId, Persona persona, SessionStatus status,
                                 OffsetDateTime completedAt) {
        String sessionId = "sess_" + UUID.randomUUID();
        jdbc.update("""
                insert into session (session_id, user_id, document_id, retry_of_session_id, mode, job_role,
                                     question_count, persona, hide_question_text, status, completed_at,
                                     created_at, updated_at)
                values (?, ?, ?, null, 'PRACTICE', '백엔드', 6, ?, false, ?, ?, now(), now())
                """, sessionId, uid, documentId, persona.name(), status.name(),
                completedAt == null ? null : completedAt);
        return sessionId;
    }

    private long insertProcessingReport(String sessionId) {
        return jdbc.queryForObject("""
                insert into report (session_id, public_id, status, attempt, created_at)
                values (?, ?, 'PROCESSING', 1, now())
                returning report_id
                """, Long.class, sessionId, UUID.randomUUID());
    }

    private String insertUser(String nickname) {
        User u = User.create(null, nickname);
        jdbc.update("insert into users (user_id, email, nickname, created_at) values (?, null, ?, now())",
                u.getUserId(), u.getNickname());
        return u.getUserId();
    }

    private Long insertDocument(String uid) {
        return jdbc.queryForObject("""
                insert into document (user_id, public_id, doc_type, doc_title, doc_text, status,
                                      source_type, created_at, updated_at)
                values (?, ?, 'RESUME', '이력서', '본문', 'READY', 'MARKDOWN', now(), now())
                returning doc_id
                """, Long.class, uid, UUID.randomUUID());
    }

    private java.util.List<String> earned(String uid) {
        return userBadgeRepository.findEarnedBadgeCodes(uid);
    }

    private int countUserBadge(String uid, String code) {
        Integer n = jdbc.queryForObject("""
                select count(*) from user_badge ub join badge b on b.badge_id = ub.badge_id
                 where ub.user_id = ? and b.badge_code = ?
                """, Integer.class, uid, code);
        return n == null ? 0 : n;
    }
}
