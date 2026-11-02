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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 답변 제한 시간({@code duration_limit}) 저장·재조회를 실제 PostgreSQL 로 봅니다(Issue #99).
 *
 * <p>mock 리포지토리는 {@code save()} 가 아무 일도 하지 않아 컬럼 매핑(이름·nullable)이
 * 틀려도 초록불입니다. 세션 옵션 조회 API 가 쓰는 {@link InterviewSessionRepository#findBySessionIdAndUser_UserId}
 * 경로로 60·90·120·null 을 넣고 다시 읽어, 컬럼 왕복과 과거 null 세션 호환을 DB 로 확인합니다.
 * Docker 가 없으면 건너뜁니다.
 */
class InterviewSessionDurationLimitRepositoryTest extends PostgresRepositoryTest {

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private TestEntityManager em;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = em.persist(User.create(null, "면접자"));
    }

    private Document resume() {
        return em.persist(Document.ofMarkdown(
                owner, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/resume.txt",
                DocumentStatus.READY));
    }

    private void persistSession(String id, Integer durationLimit) {
        persistSession(id, durationLimit, SessionStatus.IN_PROGRESS);
    }

    private void persistSession(String id, Integer durationLimit, SessionStatus status) {
        em.persist(InterviewSession.builder()
                .sessionId(id).user(owner).document(resume())
                .mode("PRACTICE").jobRole("백엔드 개발").questionCount(6)
                .persona(Persona.PRESSURE).hideQuestionText(true)
                .durationLimit(durationLimit)
                .status(status).build());
    }

    private Integer reloadDurationLimit(String id) {
        em.flush();
        em.clear();
        return sessionRepository.findBySessionIdAndUser_UserId(id, owner.getUserId())
                .orElseThrow().getDurationLimit();
    }

    @Test
    void 허용값_60_90_120_이_그대로_저장되고_재조회된다() {
        persistSession("sess_60", 60);
        persistSession("sess_90", 90);
        persistSession("sess_120", 120);

        em.flush();
        em.clear();

        assertThat(sessionRepository.findBySessionIdAndUser_UserId("sess_60", owner.getUserId())
                .orElseThrow().getDurationLimit()).isEqualTo(60);
        assertThat(sessionRepository.findBySessionIdAndUser_UserId("sess_90", owner.getUserId())
                .orElseThrow().getDurationLimit()).isEqualTo(90);
        assertThat(sessionRepository.findBySessionIdAndUser_UserId("sess_120", owner.getUserId())
                .orElseThrow().getDurationLimit()).isEqualTo(120);
    }

    @Test
    void 제한없음_null_이_null_로_저장되고_재조회된다() {
        persistSession("sess_null", null);

        assertThat(reloadDurationLimit("sess_null")).isNull();
    }

    @Test
    void 진행중_세션도_저장값이_그대로_재조회된다() {
        // 세션 옵션 조회는 완료 상태 제약이 없어 IN_PROGRESS 세션도 그대로 읽힌다.
        persistSession("sess_live", 90);

        InterviewSession found = sessionRepository
                .findBySessionIdAndUser_UserId("sess_live", owner.getUserId())
                .orElseThrow();
        assertThat(found.getStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(found.getDurationLimit()).isEqualTo(90);
        assertThat(found.isHideQuestionText()).isTrue();
    }

    @Test
    void 완료된_COMPLETED_세션도_저장값이_그대로_재조회된다() {
        // 세션 옵션 조회(getOwnedSession)는 상태로 막지 않는다.
        persistSession("sess_done", 120, SessionStatus.COMPLETED);

        InterviewSession found = sessionRepository
                .findBySessionIdAndUser_UserId("sess_done", owner.getUserId())
                .orElseThrow();
        assertThat(found.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(found.getDurationLimit()).isEqualTo(120);
    }

    @Test
    void 중단된_ABORTED_세션도_옵션조회_경로로_그대로_재조회된다() {
        // 세션 옵션 조회(InterviewSessionOptionsService.getSessionOptions →
        // InterviewSessionQueryService.getOwnedSession → findBySessionIdAndUser_UserId)는
        // 소유권만 보고 상태로 거르지 않는다. 재연습 옵션 조회(getReplayRoot)가 ABORTED 를
        // SESSION_NOT_COMPLETED(409) 로 막는 것과 달리, 이 경로는 ABORTED 세션도 그대로
        // 돌려주므로 서비스가 200 으로 옵션을 내려줄 수 있다. DB 왕복으로 상태 무관 조회를 고정.
        persistSession("sess_aborted", 60, SessionStatus.ABORTED);

        InterviewSession found = sessionRepository
                .findBySessionIdAndUser_UserId("sess_aborted", owner.getUserId())
                .orElseThrow();
        assertThat(found.getStatus()).isEqualTo(SessionStatus.ABORTED);
        assertThat(found.getDurationLimit()).isEqualTo(60);
        assertThat(found.isHideQuestionText()).isTrue();
    }

    @Test
    void 다른_사용자의_ABORTED_세션은_조회되지_않는다() {
        // 소유권은 상태와 무관하게 쿼리에 묶여 있어, 남의 ABORTED 세션은 빈 결과(→ 서비스는 404).
        persistSession("sess_other_aborted", 90, SessionStatus.ABORTED);
        User stranger = em.persist(User.create(null, "다른사용자"));

        em.flush();
        em.clear();

        assertThat(sessionRepository
                .findBySessionIdAndUser_UserId("sess_other_aborted", stranger.getUserId()))
                .isEmpty();
    }
}
