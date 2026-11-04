package com.cuea.domain.report.repository;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 홈 "최근 리포트 요약" 조회({@link ReportRepository#findRecentViewable})를 실제 PostgreSQL 로 봅니다.
 *
 * <p>검증 포인트 둘입니다.
 * <ul>
 *   <li><b>리포트를 먼저 거르고 자르는지</b> — 최신 세션에 리포트가 없어도 오래된 열람 가능한 리포트가
 *       limit 만큼 나와야 합니다(Issue #107). 세션을 먼저 N건 자르는 구현은 깨집니다.</li>
 *   <li><b>세션 완료 시각으로 정렬·표시하는지</b> — 리포트를 재분석하면 {@code report.completed_at} 은
 *       다시 찍히지만, {@code session.completed_at} 은 그대로라 순서와 날짜가 흔들리지 않아야 합니다
 *       (PR #108 리뷰).</li>
 * </ul>
 *
 * <p>세션 완료 시각을 빌더로 직접 박아 정렬 결과를 결정적으로 만듭니다.
 */
class RecentReportQueryRepositoryTest extends PostgresRepositoryTest {

    private static final List<ReportStatus> VIEWABLE = List.of(ReportStatus.COMPLETED, ReportStatus.PARTIAL);
    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-10-01T09:00:00Z");

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
    void 열람_가능한_리포트를_연습을_최근에_본_순서로_준다() {
        // 삼성(10/1 완료) 먼저, 네이버(10/3 완료) 나중
        completedReport(owner, "삼성전자", "백엔드", 6, 80, BASE);
        completedReport(owner, "네이버", "프론트엔드", 9, 92, BASE.plusDays(2));
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 3));

        assertThat(rows).extracting(RecentReportRow::companyName)
                .containsExactly("네이버", "삼성전자");
        assertThat(rows.get(0).scoreTotal()).isEqualTo(92);
        assertThat(rows.get(0).practicedOn()).isEqualTo(BASE.plusDays(2));
    }

    @Test
    void 최신_세션들에_리포트가_없어도_오래된_열람가능_리포트_3건을_준다() {
        // 오래된 쪽: 열람 가능한 리포트 3건 (10/1, 10/2, 10/3)
        completedReport(owner, "회사A", "백엔드", 6, 70, BASE);
        completedReport(owner, "회사B", "백엔드", 6, 75, BASE.plusDays(1));
        completedReport(owner, "회사C", "백엔드", 6, 80, BASE.plusDays(2));
        // 최신 쪽: 리포트가 없는 세션 2건 (10/10, 10/11)
        reportlessSession(owner, BASE.plusDays(9));
        reportlessSession(owner, BASE.plusDays(10));
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 3));

        // 세션을 먼저 자르는 구현이면 0건이 된다. 리포트 먼저 거르므로 3건.
        assertThat(rows).extracting(RecentReportRow::companyName)
                .containsExactly("회사C", "회사B", "회사A");
    }

    @Test
    void 리포트를_재분석해도_세션_완료_시각_기준이라_순서와_날짜가_바뀌지_않는다() {
        // 2주 전 연습(오래된 세션) + 어제 연습(최신 세션)
        OffsetDateTime twoWeeksAgo = BASE;
        OffsetDateTime yesterday = BASE.plusDays(13);
        Report oldReport = completedReport(owner, "오래된회사", "백엔드", 6, 70, twoWeeksAgo);
        completedReport(owner, "최근회사", "백엔드", 6, 88, yesterday);
        em.flush();

        // 오래된 리포트를 "오늘" 재분석: report.completedAt 이 now() 로 다시 찍힌다.
        oldReport.finish(ReportStatus.COMPLETED, 95, 95, 95, 95, Map.of("report_status", "complete"));
        em.persist(oldReport);
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 10));

        // report.completedAt 으로 정렬했다면 재분석한 "오래된회사" 가 맨 위로 올라왔을 것이다.
        // session.completedAt 기준이라 어제 연습한 "최근회사" 가 그대로 맨 위이고, 날짜도 연습일 그대로.
        assertThat(rows).extracting(RecentReportRow::companyName)
                .containsExactly("최근회사", "오래된회사");
        assertThat(rows.get(1).practicedOn()).isEqualTo(twoWeeksAgo);
        assertThat(rows.get(1).scoreTotal()).isEqualTo(95); // 재분석된 점수는 반영
    }

    @Test
    void FAILED_와_PROCESSING_과_리포트없는_세션은_제외한다() {
        completedReport(owner, "포함회사", "백엔드", 6, 88, BASE);
        failedReport(owner, BASE.plusDays(1));
        processingReport(owner, BASE.plusDays(2));
        reportlessSession(owner, BASE.plusDays(3));
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 10));

        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.companyName()).isEqualTo("포함회사"));
    }

    @Test
    void PARTIAL_리포트도_포함하고_점수가_없으면_null_이다() {
        partialReportWithNullScore(owner, "부분회사", "데이터", BASE);
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 3));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(ReportStatus.PARTIAL);
            assertThat(row.scoreTotal()).isNull();
        });
    }

    @Test
    void 회사를_고르지_않은_연습이면_companyName_이_null_이다() {
        completedReport(owner, null, "백엔드", 6, 77, BASE);
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 3));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.companyName()).isNull();
            assertThat(row.jobRole()).isEqualTo("백엔드");
        });
    }

    @Test
    void 다른_사용자의_리포트는_나오지_않는다() {
        completedReport(owner, "내회사", "백엔드", 6, 90, BASE);
        User other = em.persist(User.create(null, "다른 사람"));
        Document otherResume = em.persist(Document.ofMarkdown(
                other, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/other.txt", DocumentStatus.READY));
        completedReportFor(other, otherResume, "남의회사", "백엔드", 6, 95, BASE.plusDays(1));
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 10));

        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.companyName()).isEqualTo("내회사"));
    }

    @Test
    void 리포트가_없는_사용자는_빈_목록이다() {
        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 3));

        assertThat(rows).isEmpty();
    }

    @Test
    void 재연습_세션의_리포트도_열람가능하면_포함한다() {
        InterviewSession root = completedSession(owner, null, "백엔드", 6, BASE);
        Report rootReport = Report.processing(root, "task_root");
        rootReport.finish(ReportStatus.COMPLETED, 70, 70, 70, 70, Map.of("k", "v"));
        em.persist(rootReport);
        // 재연습 세션(root 를 retryOf 로 가리킴) + 그 리포트
        InterviewSession retry = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(owner).document(resume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).completedAt(BASE.plusDays(1)).retryOfSession(root).build());
        Report retryReport = Report.processing(retry, "task_retry");
        retryReport.finish(ReportStatus.COMPLETED, 85, 85, 85, 85, Map.of("k", "v"));
        em.persist(retryReport);
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 10));

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(RecentReportRow::scoreTotal).containsExactlyInAnyOrder(70, 85);
    }

    @Test
    void limit_은_앞에서부터_그만큼만_가져온다() {
        completedReport(owner, "회사A", "백엔드", 6, 70, BASE);
        completedReport(owner, "회사B", "백엔드", 6, 75, BASE.plusDays(1));
        completedReport(owner, "회사C", "백엔드", 6, 80, BASE.plusDays(2));
        em.flush();
        em.clear();

        List<RecentReportRow> one = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 1));
        assertThat(one).hasSize(1);
        assertThat(one.get(0).companyName()).isEqualTo("회사C"); // 가장 최근 연습

        List<RecentReportRow> ten = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 10));
        assertThat(ten).hasSize(3);
    }

    /* ---- fixtures ---- */

    private Report completedReport(User user, String companyName, String jobRole, int questionCount,
                                   int score, OffsetDateTime sessionCompletedAt) {
        return completedReportFor(user, resume, companyName, jobRole, questionCount, score, sessionCompletedAt);
    }

    private Report completedReportFor(User user, Document doc, String companyName, String jobRole,
                                      int questionCount, int score, OffsetDateTime sessionCompletedAt) {
        Company company = companyName == null ? null : persistCompany(companyName);
        InterviewSession session = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(doc).company(company)
                .mode("PRACTICE").jobRole(jobRole).questionCount(questionCount)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).completedAt(sessionCompletedAt).build());
        Report report = Report.processing(session, "task_" + UUID.randomUUID());
        report.finish(ReportStatus.COMPLETED, score, score, score, score, Map.of("report_status", "complete"));
        return em.persist(report);
    }

    private void partialReportWithNullScore(User user, String companyName, String jobRole,
                                            OffsetDateTime sessionCompletedAt) {
        Company company = persistCompany(companyName);
        InterviewSession session = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(resume).company(company)
                .mode("PRACTICE").jobRole(jobRole).questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).completedAt(sessionCompletedAt).build());
        Report report = Report.processing(session, "task_" + UUID.randomUUID());
        report.finish(ReportStatus.PARTIAL, null, null, null, null, Map.of("report_status", "partial"));
        em.persist(report);
    }

    /** NOT NULL 컬럼을 모두 채운 기업. 기존 InterviewSessionRepositoryTest 의 기업 픽스처와 같은 모양입니다. */
    private Company persistCompany(String companyName) {
        return em.persist(Company.builder()
                .companyName(companyName).industry("IT").valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전"))).sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.now()).verified(true).build());
    }

    private void failedReport(User user, OffsetDateTime sessionCompletedAt) {
        InterviewSession session = completedSession(user, null, "백엔드", 6, sessionCompletedAt);
        Report report = Report.processing(session, "task_" + UUID.randomUUID());
        report.fail("AI_TIMEOUT");
        em.persist(report);
    }

    private void processingReport(User user, OffsetDateTime sessionCompletedAt) {
        InterviewSession session = completedSession(user, null, "백엔드", 6, sessionCompletedAt);
        em.persist(Report.processing(session, "task_" + UUID.randomUUID()));
    }

    private void reportlessSession(User user, OffsetDateTime sessionCompletedAt) {
        completedSession(user, null, "백엔드", 6, sessionCompletedAt);
    }

    private InterviewSession completedSession(User user, Company company, String jobRole, int questionCount,
                                              OffsetDateTime sessionCompletedAt) {
        return em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(resume).company(company)
                .mode("PRACTICE").jobRole(jobRole).questionCount(questionCount)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).completedAt(sessionCompletedAt).build());
    }
}
