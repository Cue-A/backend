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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 홈 "최근 리포트 요약" 조회({@link ReportRepository#findRecentViewable})를 실제 PostgreSQL 로 봅니다.
 *
 * <p>핵심은 <b>리포트를 먼저 거르고 자르는지</b> 입니다. 최신 세션들에 리포트가 없어도, 그보다 오래된
 * 열람 가능한 리포트가 정상적으로 limit 만큼 나와야 합니다(Issue #107). 세션을 먼저 N건 자르는 구현은
 * 이 테스트에서 깨집니다.
 */
class RecentReportQueryRepositoryTest extends PostgresRepositoryTest {

    private static final List<ReportStatus> VIEWABLE = List.of(ReportStatus.COMPLETED, ReportStatus.PARTIAL);

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
    void 열람_가능한_리포트를_최신_완료순으로_준다() {
        completedReport(owner, "삼성전자", "백엔드", 6, 80);   // 먼저 완료
        completedReport(owner, "네이버", "프론트엔드", 9, 92);  // 나중 완료
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 3));

        assertThat(rows).hasSize(2);
        // completedAt 내림차순으로 정렬되어야 한다 (타임스탬프가 같을 수 있어 publicId 로 안정화)
        assertThat(rows).isSortedAccordingTo(
                (a, b) -> b.completedAt().compareTo(a.completedAt()));
        assertThat(rows).extracting(RecentReportRow::companyName)
                .containsExactlyInAnyOrder("네이버", "삼성전자");
        assertThat(rows).extracting(RecentReportRow::scoreTotal)
                .containsExactlyInAnyOrder(92, 80);
    }

    @Test
    void 최신_세션들에_리포트가_없어도_오래된_열람가능_리포트_3건을_준다() {
        // 오래된 쪽: 열람 가능한 리포트 3건
        completedReport(owner, "회사A", "백엔드", 6, 70);
        completedReport(owner, "회사B", "백엔드", 6, 75);
        completedReport(owner, "회사C", "백엔드", 6, 80);
        // 최신 쪽: 리포트가 없는 세션 2건 (리포트 미요청)
        reportlessSession(owner);
        reportlessSession(owner);
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 3));

        // 세션을 먼저 자르는 구현이면 0건이 된다. 리포트 먼저 거르므로 3건.
        assertThat(rows).hasSize(3);
        assertThat(rows).isSortedAccordingTo(
                (a, b) -> b.completedAt().compareTo(a.completedAt()));
        assertThat(rows).extracting(RecentReportRow::companyName)
                .containsExactlyInAnyOrder("회사C", "회사B", "회사A");
    }

    @Test
    void FAILED_와_PROCESSING_과_리포트없는_세션은_제외한다() {
        completedReport(owner, "포함회사", "백엔드", 6, 88);
        failedReport(owner);
        processingReport(owner);
        reportlessSession(owner);
        em.flush();
        em.clear();

        List<RecentReportRow> rows = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 10));

        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.companyName()).isEqualTo("포함회사"));
    }

    @Test
    void PARTIAL_리포트도_포함하고_점수가_없으면_null_이다() {
        partialReportWithNullScore(owner, "부분회사", "데이터");
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
        completedReport(owner, null, "백엔드", 6, 77);
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
        completedReport(owner, "내회사", "백엔드", 6, 90);
        User other = em.persist(User.create(null, "다른 사람"));
        Document otherResume = em.persist(Document.ofMarkdown(
                other, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/other.txt", DocumentStatus.READY));
        completedReportFor(other, otherResume, "남의회사", "백엔드", 6, 95);
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
        InterviewSession root = completedSession(owner, null, "백엔드", 6);
        Report rootReport = Report.processing(root, "task_root");
        rootReport.finish(ReportStatus.COMPLETED, 70, 70, 70, 70, Map.of("k", "v"));
        em.persist(rootReport);
        // 재연습 세션(root 를 retryOf 로 가리킴) + 그 리포트
        InterviewSession retry = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(owner).document(resume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).retryOfSession(root).build());
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
        completedReport(owner, "회사A", "백엔드", 6, 70);
        completedReport(owner, "회사B", "백엔드", 6, 75);
        completedReport(owner, "회사C", "백엔드", 6, 80);
        em.flush();
        em.clear();

        List<RecentReportRow> one = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 1));
        assertThat(one).hasSize(1);
        // 앞에서부터 1건 = 가장 최근 완료. 완료 시각이 같을 수 있어 세 후보 중 하나임만 보장한다.
        assertThat(one.get(0).companyName()).isIn("회사A", "회사B", "회사C");

        List<RecentReportRow> ten = reportRepository.findRecentViewable(
                owner.getUserId(), VIEWABLE, PageRequest.of(0, 10));
        assertThat(ten).hasSize(3);
    }

    /* ---- fixtures ---- */

    private void completedReport(User user, String companyName, String jobRole, int questionCount, int score) {
        completedReportFor(user, resume, companyName, jobRole, questionCount, score);
    }

    private void completedReportFor(User user, Document doc, String companyName, String jobRole,
                                    int questionCount, int score) {
        Company company = companyName == null ? null : persistCompany(companyName);
        InterviewSession session = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(doc).company(company)
                .mode("PRACTICE").jobRole(jobRole).questionCount(questionCount)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).build());
        Report report = Report.processing(session, "task_" + UUID.randomUUID());
        report.finish(ReportStatus.COMPLETED, score, score, score, score, Map.of("report_status", "complete"));
        em.persist(report);
    }

    private void partialReportWithNullScore(User user, String companyName, String jobRole) {
        Company company = persistCompany(companyName);
        InterviewSession session = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(resume).company(company)
                .mode("PRACTICE").jobRole(jobRole).questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).build());
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

    private void failedReport(User user) {
        InterviewSession session = completedSession(user, null, "백엔드", 6);
        Report report = Report.processing(session, "task_" + UUID.randomUUID());
        report.fail("AI_TIMEOUT");
        em.persist(report);
    }

    private void processingReport(User user) {
        InterviewSession session = completedSession(user, null, "백엔드", 6);
        em.persist(Report.processing(session, "task_" + UUID.randomUUID()));
    }

    private void reportlessSession(User user) {
        completedSession(user, null, "백엔드", 6);
    }

    private InterviewSession completedSession(User user, Company company, String jobRole, int questionCount) {
        return em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(resume).company(company)
                .mode("PRACTICE").jobRole(jobRole).questionCount(questionCount)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).build());
    }
}
