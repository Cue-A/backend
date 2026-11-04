package com.cuea.domain.interview.service;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.interview.dto.response.BookmarkResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionId;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.BookmarkedQuestionRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 북마크 행과 {@code question.is_bookmarked} 가 실제 DB 에서 함께 바뀌는지 봅니다.
 *
 * <p>담기는 네이티브 INSERT, 빼기는 JPQL 벌크 DELETE 라 영속성 컨텍스트를 거치지 않고, 플래그는 더티 체킹으로
 * 나중에 flush 됩니다. mock 테스트로는 둘이 정말 같이 저장되는지 알 수 없어, 매번 flush · clear 뒤 DB 에서
 * 다시 읽습니다.
 */
@Import(BookmarkService.class)
class BookmarkServiceIntegrationTest extends PostgresRepositoryTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-01T00:00:00Z");

    @Autowired
    private BookmarkService service;

    @Autowired
    private BookmarkedQuestionRepository bookmarkRepository;

    @Autowired
    private TestEntityManager em;

    private User user;
    private InterviewSession session;

    @BeforeEach
    void setUp() {
        user = em.persist(User.create(null, "면접자"));
        Document resume = em.persist(Document.ofMarkdown(
                user, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/resume.txt", DocumentStatus.READY));
        session = em.persist(InterviewSession.builder()
                .sessionId("sess_" + UUID.randomUUID()).user(user).document(resume)
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(Persona.FRIENDLY).hideQuestionText(false)
                .status(SessionStatus.IN_PROGRESS).createdAt(T0).build());
        question("q_4", QuestionType.QUESTION, "프로젝트 경험을 말씀해 주세요.", null);
        question("q_4r", QuestionType.REASK, "수치로 다시 설명해 주시겠어요?", "q_4");
        flushAndClear();
    }

    @Test
    void 담고_빼면_행과_플래그가_같이_바뀐다() {
        service.setBookmarked(user.getUserId(), session.getSessionId(), "q_4", true);
        flushAndClear();
        assertThat(isBookmarkedInDb("q_4")).isTrue();
        assertThat(hasRow("q_4")).isTrue();

        service.setBookmarked(user.getUserId(), session.getSessionId(), "q_4", false);
        flushAndClear();
        assertThat(isBookmarkedInDb("q_4")).isFalse();
        assertThat(hasRow("q_4")).isFalse();
    }

    @Test
    void 질문은행에서_빼면_플래그도_꺼진다() {
        service.setBookmarked(user.getUserId(), session.getSessionId(), "q_4", true);
        flushAndClear();

        service.delete(user.getUserId(), session.getSessionId(), "q_4");
        flushAndClear();

        assertThat(isBookmarkedInDb("q_4")).isFalse();
        assertThat(hasRow("q_4")).isFalse();
    }

    @Test
    void 목록에서_되묻기에는_원래_질문_문구가_붙는다() {
        service.setBookmarked(user.getUserId(), session.getSessionId(), "q_4r", true);
        flushAndClear();

        assertThat(service.list(user.getUserId(), 0, 20).bookmarks())
                .extracting(BookmarkResponse::questionId, BookmarkResponse::originalQuestionText,
                        BookmarkResponse::jobRole)
                .containsExactly(tuple("q_4r", "프로젝트 경험을 말씀해 주세요.", "백엔드"));
    }

    private boolean isBookmarkedInDb(String questionId) {
        return em.find(Question.class, new QuestionId(session.getSessionId(), questionId)).isBookmarked();
    }

    private boolean hasRow(String questionId) {
        return bookmarkRepository.findOwned(user.getUserId(), session.getSessionId(), questionId).isPresent();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private void question(String questionId, QuestionType type, String text, String reaskOf) {
        em.persist(Question.builder()
                .sessionId(session.getSessionId()).questionId(questionId).type(type)
                .text(text).reaskOf(reaskOf)
                .questionNumber(1).topicIndex(1).createdAt(T0).build());
    }
}
