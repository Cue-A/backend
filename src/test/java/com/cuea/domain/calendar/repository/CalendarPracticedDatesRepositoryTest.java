package com.cuea.domain.calendar.repository;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캘린더 {@code practicedDates} 월 범위 창구(Issue #130)를 실제 PostgreSQL 로 봅니다. 완료 세션의
 * KST 날짜를 그 달 경계 안에서만, 중복 없이, 상태 필터와 함께 돌려주는지 확인합니다. 홈 활동 집계와
 * 같은 KST 변환이며 사용자 등록 일정(calendar_event)과 무관합니다(읽기 전용 재사용).
 */
class CalendarPracticedDatesRepositoryTest extends PostgresRepositoryTest {

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

    private InterviewSession session(SessionStatus status, OffsetDateTime completedAt) {
        return em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(owner).document(resume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(status).completedAt(completedAt).build());
    }

    @Test
    void 그_달_완료세션_KST_날짜만_중복없이_오름차순으로_준다() {
        // 2026-08 KST 구간: [2026-07-31T15:00Z, 2026-08-31T15:00Z)
        OffsetDateTime start = OffsetDateTime.parse("2026-07-31T15:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-31T15:00:00Z");

        // 7-31 23:00 KST(=14:00Z) → 범위 밖(KST 7-31)
        session(SessionStatus.COMPLETED, OffsetDateTime.parse("2026-07-31T14:00:00Z"));
        // 8-01 00:30 KST 두 건 → 한 날로 묶임
        session(SessionStatus.COMPLETED, OffsetDateTime.parse("2026-07-31T15:30:00Z"));
        session(SessionStatus.COMPLETED, OffsetDateTime.parse("2026-07-31T16:00:00Z"));
        // 8-14 KST
        session(SessionStatus.COMPLETED, OffsetDateTime.parse("2026-08-14T05:00:00Z"));
        // 9-01 00:00 KST(=8-31 15:00Z) → 범위 밖(경계 제외)
        session(SessionStatus.COMPLETED, OffsetDateTime.parse("2026-08-31T15:00:00Z"));
        em.flush();
        em.clear();

        List<String> dates = sessionRepository.findCompletedDatesInKstBetween(owner.getUserId(), start, end);

        assertThat(dates).containsExactly("2026-08-01", "2026-08-14");
    }

    @Test
    void 완료가_아닌_세션은_제외한다() {
        OffsetDateTime start = OffsetDateTime.parse("2026-07-31T15:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-31T15:00:00Z");

        session(SessionStatus.COMPLETED, OffsetDateTime.parse("2026-08-10T05:00:00Z"));
        session(SessionStatus.ABORTED, OffsetDateTime.parse("2026-08-11T05:00:00Z"));
        session(SessionStatus.IN_PROGRESS, null);
        em.flush();
        em.clear();

        assertThat(sessionRepository.findCompletedDatesInKstBetween(owner.getUserId(), start, end))
                .containsExactly("2026-08-10");
    }

    @Test
    void 완료기록이_없는_달은_빈_목록이다() {
        OffsetDateTime start = OffsetDateTime.parse("2026-07-31T15:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-31T15:00:00Z");
        session(SessionStatus.COMPLETED, OffsetDateTime.parse("2026-09-05T05:00:00Z"));
        em.flush();
        em.clear();

        assertThat(sessionRepository.findCompletedDatesInKstBetween(owner.getUserId(), start, end)).isEmpty();
    }
}
