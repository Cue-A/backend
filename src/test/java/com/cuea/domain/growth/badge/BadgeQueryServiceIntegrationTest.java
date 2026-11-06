package com.cuea.domain.growth.badge;

import com.cuea.common.config.ClockConfig;
import com.cuea.domain.growth.dto.response.BadgeView;
import com.cuea.domain.growth.repository.BadgeRepository;
import com.cuea.domain.growth.repository.UserBadgeRepository;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 성장 관리 배지 조회(BadgeQueryService)를 실제 PostgreSQL 로 검증합니다(Issue #131).
 *
 * <p>정의 5종이 항상 나오고, 획득/미획득이 {@code earnedAt} 으로 구분되며, 다른 사용자 획득이
 * 섞이지 않고, 조회 시 lazy reconciliation 으로 미지급 배지가 복구되는지 봅니다.
 */
@Import({BadgeQueryService.class, BadgeGrantService.class, InterviewSessionQueryService.class,
        ReportSummaryQueryService.class, ClockConfig.class})
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class BadgeQueryServiceIntegrationTest extends PostgresRepositoryTest {

    @Autowired
    private BadgeQueryService queryService;
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

    @BeforeEach
    void setUp() {
        userId = insertUser("면접자");
        otherUserId = insertUser("다른 사람");
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
    void 아무것도_안한_사용자도_5종이_모두_미획득으로_나온다() {
        List<BadgeView> badges = queryService.overview(userId);

        assertThat(badges).hasSize(5);
        assertThat(badges).extracting(BadgeView::badgeCode).containsExactly(
                "FIRST_SESSION", "STREAK_3", "PRESSURE_CLEAR", "RISING", "TEN_SESSIONS");
        assertThat(badges).allSatisfy(b -> assertThat(b.earnedAt()).isNull());
    }

    @Test
    void tone_과_name_이_프론트_계약대로_내려간다() {
        List<BadgeView> badges = queryService.overview(userId);

        assertThat(badges).extracting(BadgeView::badgeCode, BadgeView::badgeName, BadgeView::tone)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FIRST_SESSION", "첫 연습", "brand"),
                        org.assertj.core.groups.Tuple.tuple("STREAK_3", "3일 연속", "warning"),
                        org.assertj.core.groups.Tuple.tuple("PRESSURE_CLEAR", "압박 극복", "danger"),
                        org.assertj.core.groups.Tuple.tuple("RISING", "우상향", "info"),
                        org.assertj.core.groups.Tuple.tuple("TEN_SESSIONS", "10회 달성", "success"));
    }

    @Test
    void 조회_시_lazy_reconciliation_으로_미지급_배지가_복구된다() {
        // 완료 세션은 있지만 지급 이벤트가 유실된 상황(직접 reconcile 호출 전)
        completed(userId, docId, Persona.PRESSURE, utc("2026-11-01T01:00:00Z"));

        List<BadgeView> badges = queryService.overview(userId);

        BadgeView first = find(badges, "FIRST_SESSION");
        BadgeView pressure = find(badges, "PRESSURE_CLEAR");
        assertThat(first.earnedAt()).isNotNull();   // 조회가 복구 지급
        assertThat(pressure.earnedAt()).isNotNull();
        assertThat(find(badges, "STREAK_3").earnedAt()).isNull();
    }

    @Test
    void 획득_배지에만_earnedAt_이_있고_직렬화에_적합한_시각이다() {
        completed(userId, docId, Persona.FRIENDLY, utc("2026-11-01T01:00:00Z"));
        queryService.overview(userId); // 지급

        BadgeView first = find(queryService.overview(userId), "FIRST_SESSION");
        assertThat(first.earnedAt()).isNotNull();
        assertThat(first.earnedAt()).isInstanceOf(OffsetDateTime.class);
    }

    @Test
    void 다른_사용자_획득내역은_노출되지_않는다() {
        Long otherDoc = insertDocument(otherUserId);
        completed(otherUserId, otherDoc, Persona.FRIENDLY, utc("2026-11-01T01:00:00Z"));
        grantService.reconcile(otherUserId);

        List<BadgeView> mine = queryService.overview(userId);
        assertThat(mine).allSatisfy(b -> assertThat(b.earnedAt()).isNull());
    }

    /* ---- fixtures ---- */

    private static OffsetDateTime utc(String iso) {
        return OffsetDateTime.parse(iso);
    }

    private BadgeView find(List<BadgeView> badges, String code) {
        return badges.stream().filter(b -> b.badgeCode().equals(code)).findFirst().orElseThrow();
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

    private void completed(String uid, Long documentId, Persona persona, OffsetDateTime completedAt) {
        jdbc.update("""
                insert into session (session_id, user_id, document_id, retry_of_session_id, mode, job_role,
                                     question_count, persona, hide_question_text, status, completed_at,
                                     created_at, updated_at)
                values (?, ?, ?, null, 'PRACTICE', '백엔드', 6, ?, false, 'COMPLETED', ?, now(), now())
                """, "sess_" + UUID.randomUUID(), uid, documentId, persona.name(),
                completedAt.withOffsetSameInstant(ZoneOffset.UTC));
    }
}
