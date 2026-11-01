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

    private Page<InterviewSession> findRecords(List<SessionStatus> statuses, UUID folderId, String pattern) {
        em.flush();
        em.clear();
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
