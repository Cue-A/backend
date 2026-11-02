package com.cuea.domain.interview.repository;

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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 폴더 조회의 소유자 조건, 세션 수 집계, 삭제 시 세션이 남는지를 실제 PostgreSQL 로 봅니다. */
class FolderRepositoryTest extends PostgresRepositoryTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-07-01T00:00:00Z");

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private TestEntityManager em;

    private User user;
    private User other;
    private Document resume;

    @BeforeEach
    void setUp() {
        user = em.persist(User.create(null, "면접자"));
        other = em.persist(User.create(null, "남"));
        resume = em.persist(Document.ofMarkdown(
                user, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/resume.txt", DocumentStatus.READY));
    }

    @Test
    void 본인_폴더만_만든_순서로_준다() {
        Folder second = folder(user, "카카오", 2);
        Folder first = folder(user, "삼성전자", 1);
        Folder others = folder(other, "남의 폴더", 0);
        flushAndClear();

        assertThat(folderRepository.findAllByUser_UserIdOrderByCreatedAtAscFolderIdAsc(user.getUserId()))
                .extracting(Folder::getFolderName)
                .containsExactly(first.getFolderName(), second.getFolderName());
        assertThat(folderRepository.findByPublicIdAndUser_UserId(others.getPublicId(), user.getUserId())).isEmpty();
        assertThat(folderRepository.countByUser_UserId(user.getUserId())).isEqualTo(2);
    }

    @Test
    void 이름_중복은_내_폴더_안에서만_본다() {
        folder(other, "삼성전자", 0);
        flushAndClear();

        assertThat(folderRepository.existsByUser_UserIdAndFolderName(user.getUserId(), "삼성전자")).isFalse();
        folder(user, "삼성전자", 1);
        assertThat(folderRepository.existsByUser_UserIdAndFolderName(user.getUserId(), "삼성전자")).isTrue();
    }

    /** 폴더 카드의 수는 연습 기록 목록과 같은 기준이어야 합니다. 중단 · 남의 세션 · 폴더 없는 세션은 빠집니다. */
    @Test
    void 폴더별_세션_수는_중단된_세션을_세지_않는다() {
        Folder samsung = folder(user, "삼성전자", 1);
        Folder empty = folder(user, "기타", 2);
        session(user, samsung, SessionStatus.COMPLETED);
        session(user, samsung, SessionStatus.IN_PROGRESS);
        session(user, samsung, SessionStatus.ABORTED);
        session(user, null, SessionStatus.COMPLETED);
        session(user, null, SessionStatus.ABORTED);
        flushAndClear();

        assertThat(sessionRepository.countSessionsPerFolder(user.getUserId(), SessionStatus.LISTED))
                .extracting(FolderSessionCount::folderId, FolderSessionCount::sessionCount)
                .containsExactly(tuple(samsung.getFolderId(), 2L));
        assertThat(sessionRepository.countByFolder_FolderIdAndStatusIn(empty.getFolderId(), SessionStatus.LISTED))
                .isZero();
        assertThat(sessionRepository.countByUser_UserIdAndStatusIn(user.getUserId(), SessionStatus.LISTED))
                .isEqualTo(3);
    }

    /** 폴더를 지워도 연습 기록은 남아야 합니다. folder_id 만 비워집니다. */
    @Test
    void 폴더를_지우면_세션은_남고_폴더에서만_빠진다() {
        Folder samsung = folder(user, "삼성전자", 1);
        InterviewSession inFolder = session(user, samsung, SessionStatus.COMPLETED);
        flushAndClear();

        folderRepository.delete(folderRepository.findByPublicIdAndUser_UserId(
                samsung.getPublicId(), user.getUserId()).orElseThrow());
        flushAndClear();

        InterviewSession reloaded = sessionRepository
                .findBySessionIdAndUser_UserId(inFolder.getSessionId(), user.getUserId())
                .orElseThrow();
        assertThat(reloaded.getFolder()).isNull();
        assertThat(folderRepository.countByUser_UserId(user.getUserId())).isZero();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private Folder folder(User owner, String name, int minutes) {
        return em.persist(Folder.builder().publicId(UUID.randomUUID()).user(owner)
                .folderName(name).createdAt(T0.plusMinutes(minutes)).build());
    }

    private InterviewSession session(User owner, Folder folder, SessionStatus status) {
        return em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(owner).document(resume).folder(folder)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(status).createdAt(T0).build());
    }
}
