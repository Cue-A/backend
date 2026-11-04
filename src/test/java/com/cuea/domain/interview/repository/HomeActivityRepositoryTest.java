package com.cuea.domain.interview.repository;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 홈 활동 집계 네이티브 쿼리(Issue #113·#114)를 실제 PostgreSQL 로 봅니다. 핵심은 <b>KST 날짜 경계</b>:
 * {@code timestamptz} 를 {@code AT TIME ZONE 'Asia/Seoul'} 로 옮겨 UTC 로는 다른 날이어도 KST 에서
 * 같은 날이면 한 날로 묶이는지, 완료 세션만·재연습 포함으로 세는지 확인합니다.
 */
class HomeActivityRepositoryTest extends PostgresRepositoryTest {

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private TestEntityManager em;

    private User owner;
    private Document resume;

    @BeforeEach
    void setUp() {
        owner = em.persist(User.create(null, "면접자"));
        resume = em.persist(Document.ofMarkdown(
                owner, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/resume.txt", DocumentStatus.READY));
    }

    @Test
    void UTC_로는_다른_날이어도_KST_로_같은_날이면_한_날로_묶는다() {
        // 2026-11-10T20:00Z = 2026-11-11 05:00 KST
        completedAt(OffsetDateTime.parse("2026-11-10T20:00:00Z"));
        // 2026-11-10T16:00Z = 2026-11-11 01:00 KST  (같은 KST 날짜)
        completedAt(OffsetDateTime.parse("2026-11-10T16:00:00Z"));
        // 2026-11-10T14:00Z = 2026-11-10 23:00 KST  (다른 KST 날짜)
        completedAt(OffsetDateTime.parse("2026-11-10T14:00:00Z"));
        em.flush();
        em.clear();

        List<String> dates = sessionRepository.findCompletedDatesInKst(owner.getUserId());

        assertThat(dates).containsExactly("2026-11-10", "2026-11-11");
    }

    @Test
    void 완료_세션만_날짜로_집계하고_중단_진행중은_뺀다() {
        completedAt(OffsetDateTime.parse("2026-11-11T02:00:00Z"));
        session(SessionStatus.ABORTED, OffsetDateTime.parse("2026-11-12T02:00:00Z"), null);
        session(SessionStatus.IN_PROGRESS, null, null);
        em.flush();
        em.clear();

        List<String> dates = sessionRepository.findCompletedDatesInKst(owner.getUserId());

        assertThat(dates).containsExactly("2026-11-11");
    }

    @Test
    void 날짜별_완료_수를_KST_로_세고_하루_여러건을_합친다() {
        // 2026-11-11 KST 두 건
        completedAt(OffsetDateTime.parse("2026-11-11T01:00:00Z")); // 10:00 KST
        completedAt(OffsetDateTime.parse("2026-11-10T20:00:00Z")); // 05:00 KST (11일)
        // 2026-11-12 KST 한 건
        completedAt(OffsetDateTime.parse("2026-11-12T03:00:00Z")); // 12:00 KST
        em.flush();
        em.clear();

        OffsetDateTime start = LocalDate.of(2026, 11, 1).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        OffsetDateTime end = LocalDate.of(2026, 11, 30).plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime();

        List<DailyActivityRow> rows = sessionRepository.countDailyActivityInKst(owner.getUserId(), start, end);

        assertThat(rows).extracting(DailyActivityRow::date)
                .containsExactly("2026-11-11", "2026-11-12");
        assertThat(rows.get(0).count()).isEqualTo(2);
        assertThat(rows.get(1).count()).isEqualTo(1);
    }

    @Test
    void 기간_반열림_구간_밖은_제외한다() {
        // 기간: 2026-11-11 00:00 KST (= 11-10T15:00Z) 포함 ~ 2026-11-12 00:00 KST (= 11-11T15:00Z) 제외
        OffsetDateTime start = OffsetDateTime.parse("2026-11-10T15:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-11-11T15:00:00Z");

        completedAt(OffsetDateTime.parse("2026-11-10T15:00:00Z")); // 경계 시작 포함 (11-11 00:00 KST)
        completedAt(OffsetDateTime.parse("2026-11-11T14:59:00Z")); // 11-11 23:59 KST 포함
        completedAt(OffsetDateTime.parse("2026-11-11T15:00:00Z")); // 경계 끝 제외 (11-12 00:00 KST)
        completedAt(OffsetDateTime.parse("2026-11-10T14:59:00Z")); // 시작 직전 제외
        em.flush();
        em.clear();

        long total = sessionRepository.countCompletedInPeriod(owner.getUserId(), start, end);
        List<DailyActivityRow> rows = sessionRepository.countDailyActivityInKst(owner.getUserId(), start, end);

        assertThat(total).isEqualTo(2);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.date()).isEqualTo("2026-11-11");
            assertThat(row.count()).isEqualTo(2);
        });
    }

    @Test
    void 재연습_완료_세션도_활동으로_센다() {
        InterviewSession root = completedAt(OffsetDateTime.parse("2026-11-11T02:00:00Z"));
        em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(owner).document(resume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).retryOfSession(root)
                .completedAt(OffsetDateTime.parse("2026-11-11T05:00:00Z")).build());
        em.flush();
        em.clear();

        OffsetDateTime start = OffsetDateTime.parse("2026-11-10T15:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-11-11T15:00:00Z");
        assertThat(sessionRepository.countCompletedInPeriod(owner.getUserId(), start, end)).isEqualTo(2);
    }

    @Test
    void 다른_사용자_세션은_집계에_없다() {
        completedAt(OffsetDateTime.parse("2026-11-11T02:00:00Z"));
        User other = em.persist(User.create(null, "다른 사람"));
        Document otherResume = em.persist(Document.ofMarkdown(
                other, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/other.txt", DocumentStatus.READY));
        em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(other).document(otherResume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED)
                .completedAt(OffsetDateTime.parse("2026-11-11T03:00:00Z")).build());
        em.flush();
        em.clear();

        assertThat(sessionRepository.findCompletedDatesInKst(owner.getUserId()))
                .containsExactly("2026-11-11");
    }

    @Test
    void 완료_기록이_없으면_빈_목록이다() {
        assertThat(sessionRepository.findCompletedDatesInKst(owner.getUserId())).isEmpty();
    }

    /* ---- fixtures ---- */

    private InterviewSession completedAt(OffsetDateTime completedAt) {
        return session(SessionStatus.COMPLETED, completedAt, null);
    }

    private InterviewSession session(SessionStatus status, OffsetDateTime completedAt, InterviewSession retryOf) {
        return em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(owner).document(resume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(status).completedAt(completedAt).retryOfSession(retryOf).build());
    }
}
