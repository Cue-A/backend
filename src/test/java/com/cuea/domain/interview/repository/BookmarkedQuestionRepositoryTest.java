package com.cuea.domain.interview.repository;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
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

/**
 * 북마크의 소유자 스코프와 멱등 삽입(ON CONFLICT)을 실제 PostgreSQL 로 봅니다.
 *
 * <p>삽입은 네이티브 쿼리라 영속성 컨텍스트를 거치지 않으므로, 확인 전에 {@code em.clear()} 로
 * 1차 캐시를 비웁니다.
 */
class BookmarkedQuestionRepositoryTest extends PostgresRepositoryTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-01T00:00:00Z");

    @Autowired
    private BookmarkedQuestionRepository bookmarkRepository;

    @Autowired
    private TestEntityManager em;

    private User user;
    private User other;
    private InterviewSession session;

    @BeforeEach
    void setUp() {
        user = em.persist(User.create(null, "면접자"));
        other = em.persist(User.create(null, "남"));
        Document resume = em.persist(Document.ofMarkdown(
                user, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/resume.txt", DocumentStatus.READY));
        session = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(resume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.IN_PROGRESS).createdAt(T0).build());
        question("q_4", QuestionType.QUESTION, null);
        em.flush();
    }

    /** 연타나 재시도로 같은 요청이 두 번 와도 UNIQUE 위반(500) 없이 한 행만 남아야 합니다. */
    @Test
    void 같은_질문을_두_번_담아도_한_행만_남는다() {
        assertThat(bookmarkRepository.insertIfAbsent(user.getUserId(), session.getSessionId(), "q_4")).isEqualTo(1);
        assertThat(bookmarkRepository.insertIfAbsent(user.getUserId(), session.getSessionId(), "q_4")).isZero();
        em.clear();

        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void 내_북마크만_지운다() {
        bookmarkRepository.insertIfAbsent(other.getUserId(), session.getSessionId(), "q_4");
        bookmarkRepository.insertIfAbsent(user.getUserId(), session.getSessionId(), "q_4");

        assertThat(bookmarkRepository.deleteOwned(user.getUserId(), session.getSessionId(), "q_4")).isEqualTo(1);
        assertThat(bookmarkRepository.deleteOwned(user.getUserId(), session.getSessionId(), "q_4")).isZero();
        em.clear();

        assertThat(countRows()).isEqualTo(1);
    }

    private long countRows() {
        return em.getEntityManager()
                .createQuery("select count(b) from BookmarkedQuestion b", Long.class)
                .getSingleResult();
    }

    private Question question(String questionId, QuestionType type, String reaskOf) {
        return em.persist(Question.builder()
                .sessionId(session.getSessionId()).questionId(questionId).type(type)
                .text("질문 " + questionId).reaskOf(reaskOf)
                .questionNumber(1).topicIndex(1).createdAt(T0).build());
    }
}
