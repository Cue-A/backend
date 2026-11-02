package com.cuea.domain.report.repository;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 홈 점수 집계 네이티브 쿼리(Issue #114)를 실제 PostgreSQL 로 봅니다. 점수 있는 COMPLETED·PARTIAL 만
 * 평균·최고·추이에 들어가고, FAILED·PROCESSING·{@code score_total=null} 은 빠지며, 기간은 리포트
 * 완료 시각(KST) 기준이고, 소유권은 세션 조인으로 제한되는지 확인합니다.
 */
class HomeScoreRepositoryTest extends PostgresRepositoryTest {

    /** 2026-11 한 달 전체(KST) 반열림 구간. */
    private static final OffsetDateTime NOV_START =
            OffsetDateTime.parse("2026-10-31T15:00:00Z"); // 2026-11-01 00:00 KST
    private static final OffsetDateTime NOV_END =
            OffsetDateTime.parse("2026-11-30T15:00:00Z"); // 2026-12-01 00:00 KST

    @Autowired
    private ReportRepository reportRepository;

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
    void 평균과_최고는_점수있는_COMPLETED_PARTIAL_만_센다() {
        report(owner, ReportStatus.COMPLETED, 80, OffsetDateTime.parse("2026-11-05T01:00:00Z"));
        report(owner, ReportStatus.PARTIAL, 90, OffsetDateTime.parse("2026-11-06T01:00:00Z"));
        report(owner, ReportStatus.PARTIAL, null, OffsetDateTime.parse("2026-11-07T01:00:00Z")); // 점수 없음 제외
        report(owner, ReportStatus.FAILED, null, OffsetDateTime.parse("2026-11-08T01:00:00Z"));  // 제외
        report(owner, ReportStatus.PROCESSING, null, null);                                        // 제외
        em.flush();
        em.clear();

        ScoreSummaryRow summary = reportRepository.summarizeScores(owner.getUserId(), NOV_START, NOV_END);

        assertThat(summary.average().doubleValue()).isEqualTo(85.0, within(0.0001)); // (80+90)/2
        assertThat(summary.best()).isEqualTo(90);
    }

    @Test
    void 유효_점수가_없으면_평균과_최고가_모두_null_이다() {
        report(owner, ReportStatus.PARTIAL, null, OffsetDateTime.parse("2026-11-07T01:00:00Z"));
        report(owner, ReportStatus.FAILED, null, OffsetDateTime.parse("2026-11-08T01:00:00Z"));
        em.flush();
        em.clear();

        ScoreSummaryRow summary = reportRepository.summarizeScores(owner.getUserId(), NOV_START, NOV_END);

        assertThat(summary.average()).isNull();
        assertThat(summary.best()).isNull();
    }

    @Test
    void 리포트_완료시각이_기간_밖이면_제외한다() {
        report(owner, ReportStatus.COMPLETED, 70, OffsetDateTime.parse("2026-10-31T14:00:00Z")); // 10-31 23:00 KST, 기간 밖
        report(owner, ReportStatus.COMPLETED, 88, OffsetDateTime.parse("2026-11-01T01:00:00Z")); // 기간 안
        em.flush();
        em.clear();

        ScoreSummaryRow summary = reportRepository.summarizeScores(owner.getUserId(), NOV_START, NOV_END);

        assertThat(summary.average().doubleValue()).isEqualTo(88.0, within(0.0001));
        assertThat(summary.best()).isEqualTo(88);
    }

    @Test
    void 회차_추이는_완료_최신순으로_주고_limit_을_지킨다() {
        report(owner, ReportStatus.COMPLETED, 70, OffsetDateTime.parse("2026-11-01T01:00:00Z"));
        report(owner, ReportStatus.COMPLETED, 80, OffsetDateTime.parse("2026-11-10T01:00:00Z"));
        report(owner, ReportStatus.PARTIAL, 90, OffsetDateTime.parse("2026-11-20T01:00:00Z"));
        report(owner, ReportStatus.COMPLETED, null, OffsetDateTime.parse("2026-11-21T01:00:00Z")); // 점수 없음 제외
        em.flush();
        em.clear();

        List<ScorePointRow> rows = reportRepository.findScorePointsLatestFirst(
                owner.getUserId(), NOV_START, NOV_END, PageRequest.of(0, 8));

        // 최신순: 90(11-20), 80(11-10), 70(11-01)
        assertThat(rows).extracting(ScorePointRow::score).containsExactly(90, 80, 70);

        List<ScorePointRow> limited = reportRepository.findScorePointsLatestFirst(
                owner.getUserId(), NOV_START, NOV_END, PageRequest.of(0, 2));
        assertThat(limited).extracting(ScorePointRow::score).containsExactly(90, 80);
    }

    @Test
    void 월_평균은_KST_월별로_묶고_오래된_달부터_준다() {
        OffsetDateTime wide = OffsetDateTime.parse("2026-08-31T15:00:00Z"); // 2026-09-01 00:00 KST
        OffsetDateTime wideEnd = OffsetDateTime.parse("2026-11-30T15:00:00Z");
        report(owner, ReportStatus.COMPLETED, 60, OffsetDateTime.parse("2026-09-05T01:00:00Z"));
        report(owner, ReportStatus.COMPLETED, 80, OffsetDateTime.parse("2026-09-25T01:00:00Z"));
        report(owner, ReportStatus.PARTIAL, 90, OffsetDateTime.parse("2026-10-10T01:00:00Z"));
        // KST 경계: 2026-10-31T16:00Z = 2026-11-01 01:00 KST → 11월로 묶여야 함
        report(owner, ReportStatus.COMPLETED, 100, OffsetDateTime.parse("2026-10-31T16:00:00Z"));
        em.flush();
        em.clear();

        List<MonthlyScoreRow> rows = reportRepository.findMonthlyScoreAverages(owner.getUserId(), wide, wideEnd);

        assertThat(rows).extracting(MonthlyScoreRow::yearMonth).containsExactly("2026-09", "2026-10", "2026-11");
        assertThat(rows.get(0).average().doubleValue()).isEqualTo(70.0, within(0.0001)); // (60+80)/2
        assertThat(rows.get(1).average().doubleValue()).isEqualTo(90.0, within(0.0001));
        assertThat(rows.get(2).average().doubleValue()).isEqualTo(100.0, within(0.0001)); // KST 로 11월
    }

    @Test
    void 다른_사용자_점수는_집계에_없다() {
        report(owner, ReportStatus.COMPLETED, 88, OffsetDateTime.parse("2026-11-05T01:00:00Z"));
        User other = em.persist(User.create(null, "다른 사람"));
        Document otherResume = em.persist(Document.ofMarkdown(
                other, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/other.txt", DocumentStatus.READY));
        reportFor(other, otherResume, ReportStatus.COMPLETED, 99, OffsetDateTime.parse("2026-11-06T01:00:00Z"));
        em.flush();
        em.clear();

        ScoreSummaryRow summary = reportRepository.summarizeScores(owner.getUserId(), NOV_START, NOV_END);
        assertThat(summary.average().doubleValue()).isEqualTo(88.0, within(0.0001));
        assertThat(summary.best()).isEqualTo(88);
    }

    /* ---- fixtures ---- */

    private void report(User user, ReportStatus status, Integer score, OffsetDateTime completedAt) {
        reportFor(user, resume, status, score, completedAt);
    }

    private void reportFor(User user, Document doc, ReportStatus status, Integer score, OffsetDateTime completedAt) {
        InterviewSession session = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(doc)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED)
                .completedAt(completedAt == null ? OffsetDateTime.parse("2026-11-01T00:00:00Z") : completedAt)
                .build());
        // finish() 는 completedAt 을 now() 로 덮으므로, 고정 시각을 위해 빌더로 직접 세팅합니다.
        em.persist(Report.builder()
                .publicId(UUID.randomUUID())
                .session(session)
                .status(status)
                .attempt(1)
                .scoreTotal(score)
                .completedAt(completedAt)
                .build());
    }
}
