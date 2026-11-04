package com.cuea.domain.interview.repository;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.interview.entity.BookmarkedQuestion;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionId;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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

    /** 회사를 고르지 않은 세션도 나와야 합니다. 회사를 inner join 하면 빠집니다. */
    @Test
    void 목록은_내_북마크만_담은_최신순으로_준다() {
        Question reask = question("q_4r", QuestionType.REASK, "q_4");
        BookmarkedQuestion earlier = bookmark(user, find("q_4"), 1);
        BookmarkedQuestion later = bookmark(user, reask, 2);
        bookmark(other, reask, 3);
        em.flush();
        em.clear();

        Page<BookmarkedQuestion> page = bookmarkRepository.findPageOwned(user.getUserId(), PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(BookmarkedQuestion::getBookmarkedQuestionId)
                .containsExactly(later.getBookmarkedQuestionId(), earlier.getBookmarkedQuestionId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getQuestion().getSession().getCompany()).isNull();
    }

    @Test
    void 남의_북마크는_찾지_않는다() {
        bookmark(other, find("q_4"), 1);
        em.flush();
        em.clear();

        assertThat(bookmarkRepository.findOwned(user.getUserId(), session.getSessionId(), "q_4")).isEmpty();
        assertThat(bookmarkRepository.findOwned(other.getUserId(), session.getSessionId(), "q_4")).isPresent();
    }

    private Question find(String questionId) {
        return em.find(Question.class, new QuestionId(session.getSessionId(), questionId));
    }

    private BookmarkedQuestion bookmark(User owner, Question question, int minutes) {
        return em.persist(BookmarkedQuestion.builder()
                .user(owner).question(question).createdAt(T0.plusMinutes(minutes)).build());
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
