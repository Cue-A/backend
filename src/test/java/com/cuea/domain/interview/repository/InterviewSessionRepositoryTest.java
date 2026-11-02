package com.cuea.domain.interview.repository;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.interview.entity.Folder;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 연습 기록 목록 조건(소유자 · 상태 · 폴더 · 검색어)과 회차 계산용 조회를 실제 PostgreSQL 로 봅니다. */
class InterviewSessionRepositoryTest extends PostgresRepositoryTest {

    private static final List<SessionStatus> VISIBLE = List.of(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS);
    private static final PageRequest NEWEST_FIRST =
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "sessionId"));
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-07-01T00:00:00Z");

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private TestEntityManager em;

    private User user;
    private Document resume;
    private Company samsung;

    @BeforeEach
    void setUp() {
        user = em.persist(User.create(null, "면접자"));
        resume = em.persist(Document.ofMarkdown(
                user, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/resume.txt", DocumentStatus.READY));
        samsung = em.persist(Company.builder()
                .companyName("Samsung Electronics").industry("전자").valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전"))).sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.now()).verified(true).build());
    }

    @Test
    void 본인_세션만_최신순으로_주고_넘긴_상태만_준다() {
        InterviewSession done = session(user, SessionStatus.COMPLETED, 1);
        InterviewSession running = session(user, SessionStatus.IN_PROGRESS, 2);
        session(user, SessionStatus.ABORTED, 3);
        session(em.persist(User.create(null, "남")), SessionStatus.COMPLETED, 4);

        Page<InterviewSession> page = findRecords(VISIBLE, null, "%");

        assertThat(page.getContent()).extracting(InterviewSession::getSessionId)
                .containsExactly(running.getSessionId(), done.getSessionId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(findRecords(List.of(SessionStatus.COMPLETED), null, "%").getContent())
                .extracting(InterviewSession::getSessionId)
                .containsExactly(done.getSessionId());
    }

    /** 폴더 조건이 없을 때 폴더 없는 세션까지 빠지면 안 됩니다(inner join 이면 빠짐). */
    @Test
    void 폴더를_주면_그_폴더만_안_주면_폴더_없는_세션도_준다() {
        Folder folder = em.persist(Folder.builder().publicId(UUID.randomUUID()).user(user).folderName("삼성").build());
        InterviewSession inFolder = session(user, SessionStatus.COMPLETED, 1);
        inFolder.moveTo(folder);
        InterviewSession noFolder = session(user, SessionStatus.COMPLETED, 2);
        em.flush();

        assertThat(findRecords(VISIBLE, folder.getPublicId(), "%").getContent())
                .extracting(InterviewSession::getSessionId)
                .containsExactly(inFolder.getSessionId());
        assertThat(findRecords(VISIBLE, null, "%").getContent())
                .extracting(InterviewSession::getSessionId)
                .containsExactly(noFolder.getSessionId(), inFolder.getSessionId());
    }

    /** 행마다 폴더 이름을 보여주므로 목록에서 함께 가져와야 합니다. 안 그러면 행마다 쿼리가 나갑니다. */
    @Test
    void 목록은_폴더를_함께_가져온다() {
        Folder folder = em.persist(Folder.builder().publicId(UUID.randomUUID()).user(user).folderName("삼성").build());
        session(user, SessionStatus.COMPLETED, 1).moveTo(folder);

        InterviewSession found = findRecords(VISIBLE, null, "%").getContent().get(0);

        assertThat(Hibernate.isInitialized(found.getFolder())).isTrue();
        assertThat(found.getFolder().getFolderName()).isEqualTo("삼성");
    }

    @Test
    void 검색어는_회사명이나_직무에서_찾고_회사가_없어도_직무로_찾는다() {
        InterviewSession withCompany = session(user, SessionStatus.COMPLETED, 1, samsung, "마케팅");
        InterviewSession noCompany = session(user, SessionStatus.COMPLETED, 2, null, "백엔드 개발");

        assertThat(findRecords(VISIBLE, null, "%samsung%").getContent())
                .extracting(InterviewSession::getSessionId)
                .containsExactly(withCompany.getSessionId());
        assertThat(findRecords(VISIBLE, null, "%백엔드%").getContent())
                .extracting(InterviewSession::getSessionId)
                .containsExactly(noCompany.getSessionId());
    }

    /** 검색어의 % 는 와일드카드가 아니라 글자입니다. 패턴은 서비스가 ! 로 이스케이프해 넘깁니다. */
    @Test
    void 이스케이프한_퍼센트는_글자_그대로_찾는다() {
        InterviewSession exact = session(user, SessionStatus.COMPLETED, 1, null, "qa 100%");
        session(user, SessionStatus.COMPLETED, 2, null, "qa 1000");

        assertThat(findRecords(VISIBLE, null, "%100!%%").getContent())
                .extracting(InterviewSession::getSessionId)
                .containsExactly(exact.getSessionId());
    }

    @Test
    void 재연습_조회는_중단된_재연습과_남의_세션을_뺀다() {
        InterviewSession root = session(user, SessionStatus.COMPLETED, 1);
        InterviewSession second = retry(root, SessionStatus.COMPLETED, 2);
        retry(root, SessionStatus.ABORTED, 3);
        InterviewSession third = retry(root, SessionStatus.IN_PROGRESS, 4);

        List<InterviewSession> retries = sessionRepository.findRetriesOf(
                user.getUserId(), List.of(root.getSessionId()), SessionStatus.ABORTED);

        assertThat(retries).extracting(InterviewSession::getSessionId)
                .containsExactlyInAnyOrder(second.getSessionId(), third.getSessionId());
        assertThat(sessionRepository.findRetriesOf("someone-else", List.of(root.getSessionId()), SessionStatus.ABORTED))
                .isEmpty();
    }

    /** 폴더 옮기기는 면접 단위입니다. 1회차는 retryOfSession 이 없어도 나와야 합니다. */
    @Test
    void 면접_전체_조회는_1회차와_재연습을_주고_중단과_다른_면접은_뺀다() {
        InterviewSession root = session(user, SessionStatus.COMPLETED, 1);
        InterviewSession second = retry(root, SessionStatus.COMPLETED, 2);
        retry(root, SessionStatus.ABORTED, 3);
        InterviewSession third = retry(root, SessionStatus.IN_PROGRESS, 4);
        session(user, SessionStatus.COMPLETED, 5);
        flushAndClear();

        assertThat(sessionRepository.findInterviewsOf(user.getUserId(), List.of(root.getSessionId()), VISIBLE))
                .extracting(InterviewSession::getSessionId)
                .containsExactly(root.getSessionId(), second.getSessionId(), third.getSessionId());
        assertThat(sessionRepository.findInterviewsOf("someone-else", List.of(root.getSessionId()), VISIBLE))
                .isEmpty();
    }

    @Test
    void 세션_ID_조회는_본인의_보이는_세션만_준다() {
        InterviewSession mine = session(user, SessionStatus.COMPLETED, 1);
        InterviewSession aborted = session(user, SessionStatus.ABORTED, 2);
        InterviewSession others = session(em.persist(User.create(null, "남")), SessionStatus.COMPLETED, 3);
        flushAndClear();

        assertThat(sessionRepository.findAllBySessionIdInAndUser_UserIdAndStatusIn(
                List.of(mine.getSessionId(), aborted.getSessionId(), others.getSessionId()), user.getUserId(), VISIBLE))
                .extracting(InterviewSession::getSessionId)
                .containsExactly(mine.getSessionId());
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private Page<InterviewSession> findRecords(List<SessionStatus> statuses, UUID folderId, String pattern) {
        flushAndClear();
        return sessionRepository.findRecords(user.getUserId(), statuses, folderId, pattern, NEWEST_FIRST);
    }

    private InterviewSession session(User owner, SessionStatus status, int minutes) {
        return session(owner, status, minutes, null, "백엔드");
    }

    private InterviewSession session(User owner, SessionStatus status, int minutes, Company company, String jobRole) {
        return em.persist(builder(owner, status, minutes).company(company).jobRole(jobRole).build());
    }

    private InterviewSession retry(InterviewSession root, SessionStatus status, int minutes) {
        return em.persist(builder(user, status, minutes).retryOfSession(root).build());
    }

    private InterviewSession.InterviewSessionBuilder<?, ?> builder(User owner, SessionStatus status, int minutes) {
        return InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(owner).document(resume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(status).createdAt(T0.plusMinutes(minutes));
    }
}
