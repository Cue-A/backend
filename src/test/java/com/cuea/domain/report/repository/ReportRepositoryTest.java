package com.cuea.domain.report.repository;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportRetryStatus;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동시 요청 방어의 DB 쪽 절반을 실제 PostgreSQL 로 봅니다.
 * <ul>
 *   <li>FAILED 재요청: {@code status = FAILED and attempt = 이전값} 조건부 UPDATE 는 한쪽만 성공</li>
 *   <li>첫 요청: {@code session_id} UNIQUE 로 두 번째 행이 막힘</li>
 * </ul>
 * 서비스 테스트에서는 리포지토리가 mock 이라 이 조건이 검증되지 않습니다.
 */
class ReportRepositoryTest extends PostgresRepositoryTest {

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private TestEntityManager em;

    private InterviewSession session;

    @BeforeEach
    void setUp() {
        User user = em.persist(User.create(null, "면접자"));
        Document resume = em.persist(Document.ofMarkdown(
                user, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/resume.txt", DocumentStatus.READY));
        session = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(resume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.COMPLETED).build());
    }

    @Test
    void FAILED_이고_attempt_가_같으면_1건을_PROCESSING_으로_되돌린다() {
        Report failed = failedReport(1, "AI_TIMEOUT");

        int updated = reopen(failed, 1, "task_2");

        assertThat(updated).isEqualTo(1);
        Report reopened = reload(failed);
        assertThat(reopened.getStatus()).isEqualTo(ReportStatus.PROCESSING);
        assertThat(reopened.getAttempt()).isEqualTo(2);
        assertThat(reopened.getAiTaskId()).isEqualTo("task_2");
        assertThat(reopened.getErrorCode()).isNull();
        assertThat(reopened.getCompletedAt()).isNull();
    }

    /** 재요청을 두 번 누른 경우. 먼저 도착한 쪽이 attempt 를 올려 두 번째는 조건에 맞지 않습니다. */
    @Test
    void 같은_이전_attempt_로_두_번_되돌리면_두_번째는_0건() {
        Report failed = failedReport(1, "AI_TIMEOUT");

        int first = reopen(failed, 1, "task_2");
        int second = reopen(failed, 1, "task_2");

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(reload(failed).getAttempt()).isEqualTo(2);
    }

    @Test
    void attempt_가_다르면_0건이고_그대로_둔다() {
        Report failed = failedReport(2, "AI_TIMEOUT");

        int updated = reopen(failed, 1, "task_x");

        assertThat(updated).isZero();
        Report unchanged = reload(failed);
        assertThat(unchanged.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(unchanged.getAttempt()).isEqualTo(2);
        assertThat(unchanged.getErrorCode()).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void FAILED_가_아니면_attempt_가_같아도_0건() {
        Report processing = em.persistFlushFind(Report.processing(session, "task_1"));

        int updated = reopen(processing, 1, "task_2");

        assertThat(updated).isZero();
        Report unchanged = reload(processing);
        assertThat(unchanged.getStatus()).isEqualTo(ReportStatus.PROCESSING);
        assertThat(unchanged.getAiTaskId()).isEqualTo("task_1");
    }

    @Test
    void 같은_세션에_리포트를_두_번_만들면_UNIQUE_에_걸린다() {
        reportRepository.save(Report.processing(session, "task_1"));
        em.flush();

        assertThatThrownBy(() -> {
            reportRepository.save(Report.processing(session, "task_1"));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 상태 조회 API 의 소유자 스코프. 남의 리포트는 존재 여부도 드러나지 않게 빈 값입니다. */
    @Test
    void reportId_로_찾을_때_본인_것만_나온다() {
        Report report = em.persistFlushFind(Report.processing(session, "task_1"));
        User other = em.persist(User.create(null, "다른 사람"));
        String ownerId = session.getUser().getUserId();

        assertThat(reportRepository.findByPublicIdAndSession_User_UserId(report.getPublicId(), ownerId))
                .map(Report::getReportId).contains(report.getReportId());
        assertThat(reportRepository.findByPublicIdAndSession_User_UserId(report.getPublicId(), other.getUserId()))
                .isEmpty();
        assertThat(reportRepository.findByPublicIdAndSession_User_UserId(UUID.randomUUID(), ownerId))
                .isEmpty();
    }

    /**
     * 상태 조회는 트랜잭션 없이(open-in-view 도 꺼짐) 세션 ID 를 꺼냅니다. 식별자 접근이
     * 프록시를 초기화하면 트랜잭션 밖에서 LazyInitializationException 이 납니다.
     */
    @Test
    void 조회한_리포트에서_세션_ID_를_꺼내도_세션을_읽지_않는다() {
        Report report = em.persistFlushFind(Report.processing(session, "task_1"));
        em.clear();

        Report found = reportRepository.findByPublicIdAndSession_User_UserId(
                report.getPublicId(), session.getUser().getUserId()).orElseThrow();

        assertThat(found.getSession().getSessionId()).isEqualTo(session.getSessionId());
        assertThat(Hibernate.isInitialized(found.getSession())).isFalse();
    }

    /** 재시도 중에도 리포트는 PARTIAL 결과 그대로 보여야 합니다. 상태 · 점수 · 원본을 건드리지 않습니다. */
    @Test
    void PARTIAL_재시도를_시작하면_retry_status_만_바뀌고_결과는_그대로다() {
        Report partial = partialReport(1, null, null);

        int updated = startRetry(partial, 1, "task_2");

        assertThat(updated).isEqualTo(1);
        Report started = reload(partial);
        assertThat(started.getStatus()).isEqualTo(ReportStatus.PARTIAL);
        assertThat(started.getRetryStatus()).isEqualTo(ReportRetryStatus.PROCESSING);
        assertThat(started.getAttempt()).isEqualTo(2);
        assertThat(started.getAiTaskId()).isEqualTo("task_2");
        assertThat(started.getScoreTotal()).isEqualTo(68);
        assertThat(started.getReportData()).containsEntry("report_status", "partial");
        assertThat(started.getCompletedAt()).isNotNull();
    }

    @Test
    void 재시도를_두_번_누르면_두_번째는_0건() {
        Report partial = partialReport(1, null, null);

        int first = startRetry(partial, 1, "task_2");
        int second = startRetry(partial, 1, "task_2");

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    /** 이전 재시도가 실패했으면 다시 시작할 수 있고, 남아 있던 실패 원인은 지웁니다. */
    @Test
    void 재시도가_실패한_리포트는_다시_시작할_수_있다() {
        Report partial = partialReport(2, ReportRetryStatus.FAILED, "GAZE_FAILED");

        int updated = startRetry(partial, 2, "task_3");

        assertThat(updated).isEqualTo(1);
        Report started = reload(partial);
        assertThat(started.getRetryStatus()).isEqualTo(ReportRetryStatus.PROCESSING);
        assertThat(started.getErrorCode()).isNull();
    }

    @Test
    void PARTIAL_이_아니면_재시도를_시작하지_않는다() {
        Report failed = failedReport(1, "AI_TIMEOUT");

        assertThat(startRetry(failed, 1, "task_2")).isZero();
        assertThat(reload(failed).getRetryStatus()).isNull();
    }

    private Report partialReport(int attempt, ReportRetryStatus retryStatus, String errorCode) {
        Report report = Report.processing(session, "task_" + attempt);
        report.retryWith(attempt, "task_" + attempt);
        report.finish(ReportStatus.PARTIAL, 68, 72, 61, null, Map.of("report_status", "partial"));
        if (retryStatus == ReportRetryStatus.FAILED) {
            report.failRetry(errorCode);
        }
        return em.persistFlushFind(report);
    }

    private int startRetry(Report report, int previousAttempt, String aiTaskId) {
        return reportRepository.startRetry(report.getReportId(), previousAttempt, previousAttempt + 1,
                aiTaskId, ReportStatus.PARTIAL, ReportRetryStatus.PROCESSING);
    }

    private Report failedReport(int attempt, String errorCode) {
        Report report = Report.processing(session, "task_" + attempt);
        report.retryWith(attempt, "task_" + attempt);
        report.fail(errorCode);
        return em.persistFlushFind(report);
    }

    private int reopen(Report report, int previousAttempt, String aiTaskId) {
        return reportRepository.reopenFailed(report.getReportId(), previousAttempt, previousAttempt + 1,
                aiTaskId, ReportStatus.PROCESSING, ReportStatus.FAILED);
    }

    /** 조건부 UPDATE 는 영속성 컨텍스트를 거치지 않으므로 DB 에서 다시 읽습니다. */
    private Report reload(Report report) {
        em.clear();
        return em.find(Report.class, report.getReportId());
    }
}
