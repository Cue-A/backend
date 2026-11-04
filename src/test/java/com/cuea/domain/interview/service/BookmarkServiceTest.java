package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.BookmarkListResponse;
import com.cuea.domain.interview.dto.response.BookmarkMemoResponse;
import com.cuea.domain.interview.dto.response.BookmarkResponse;
import com.cuea.domain.interview.dto.response.BookmarkToggleResponse;
import com.cuea.domain.interview.entity.BookmarkedQuestion;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.BookmarkedQuestionRepository;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 북마크 규칙(세션 상태 · 404 · 플래그 동기화)을 DB 없이 봅니다. 쿼리 자체는 BookmarkedQuestionRepositoryTest 가 봅니다. */
class BookmarkServiceTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "sess_1";
    private static final String QUESTION_ID = "q_4";

    private InterviewSessionRepository sessionRepository;
    private QuestionRepository questionRepository;
    private BookmarkedQuestionRepository bookmarkRepository;
    private BookmarkService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        questionRepository = mock(QuestionRepository.class);
        bookmarkRepository = mock(BookmarkedQuestionRepository.class);
        service = new BookmarkService(sessionRepository, questionRepository, bookmarkRepository);
    }

    @Test
    void 진행_중인_세션의_질문을_담으면_행을_넣고_플래그를_켠다() {
        givenSession(SessionStatus.IN_PROGRESS);
        Question question = givenQuestion(false);

        BookmarkToggleResponse response = service.setBookmarked(USER_ID, SESSION_ID, QUESTION_ID, true);

        verify(bookmarkRepository).insertIfAbsent(USER_ID, SESSION_ID, QUESTION_ID);
        verify(bookmarkRepository, never()).deleteOwned(anyString(), anyString(), anyString());
        assertThat(question.isBookmarked()).isTrue();
        assertThat(response.bookmarked()).isTrue();
    }

    @Test
    void 빼면_행을_지우고_플래그를_끈다() {
        givenSession(SessionStatus.IN_PROGRESS);
        Question question = givenQuestion(true);

        BookmarkToggleResponse response = service.setBookmarked(USER_ID, SESSION_ID, QUESTION_ID, false);

        verify(bookmarkRepository).deleteOwned(USER_ID, SESSION_ID, QUESTION_ID);
        verify(bookmarkRepository, never()).insertIfAbsent(anyString(), anyString(), anyString());
        assertThat(question.isBookmarked()).isFalse();
        assertThat(response.bookmarked()).isFalse();
    }

    @Test
    void 끝난_세션이면_SESSION_ENDED() {
        givenSession(SessionStatus.COMPLETED);
        givenQuestion(false);

        assertToggleRejected(() -> service.setBookmarked(USER_ID, SESSION_ID, QUESTION_ID, true),
                ErrorCode.SESSION_ENDED);
    }

    @Test
    void 중단된_세션이면_SESSION_ABORTED() {
        givenSession(SessionStatus.ABORTED);
        givenQuestion(false);

        assertToggleRejected(() -> service.setBookmarked(USER_ID, SESSION_ID, QUESTION_ID, true),
                ErrorCode.SESSION_ABORTED);
    }

    /** 남의 세션도 없는 세션과 똑같이 404 입니다. */
    @Test
    void 내_세션이_아니면_SESSION_NOT_FOUND() {
        when(sessionRepository.findBySessionIdAndUser_UserId(SESSION_ID, USER_ID)).thenReturn(Optional.empty());

        assertToggleRejected(() -> service.setBookmarked(USER_ID, SESSION_ID, QUESTION_ID, true),
                ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 세션에_없는_질문이면_QUESTION_NOT_FOUND() {
        givenSession(SessionStatus.IN_PROGRESS);
        when(questionRepository.findBySessionIdAndQuestionId(SESSION_ID, QUESTION_ID)).thenReturn(Optional.empty());

        assertToggleRejected(() -> service.setBookmarked(USER_ID, SESSION_ID, QUESTION_ID, true),
                ErrorCode.QUESTION_NOT_FOUND);
    }

    /** question_id 는 세션 스코프라 다른 세션에도 q_4 가 있습니다. 같은 세션의 q_4 를 붙여야 합니다. */
    @Test
    void 되묻기에는_같은_세션의_원래_질문_문구를_붙인다() {
        InterviewSession first = InterviewSession.builder().sessionId("sess_1").jobRole("백엔드").build();
        InterviewSession second = InterviewSession.builder().sessionId("sess_2").jobRole("백엔드").build();
        givenBookmarks(
                bookmarked(Question.builder().sessionId("sess_1").questionId("q_4r").session(first)
                        .type(QuestionType.REASK).topicIndex(1).text("수치로 다시 설명해 주시겠어요?").reaskOf("q_4").build()),
                bookmarked(Question.builder().sessionId("sess_2").questionId("q_4").session(second)
                        .type(QuestionType.QUESTION).topicIndex(1).text("다른 세션의 질문").build()));
        givenAskedInOrder(
                asked("sess_1", "q_4", QuestionType.QUESTION, 1, "프로젝트 경험을 말씀해 주세요."),
                Question.builder().sessionId("sess_1").questionId("q_4r")
                        .type(QuestionType.REASK).topicIndex(1).text("수치로 다시 설명해 주시겠어요?").reaskOf("q_4").build(),
                asked("sess_2", "q_4", QuestionType.QUESTION, 1, "다른 세션의 질문"));

        assertThat(service.list(USER_ID, 0, 20).bookmarks())
                .extracting(BookmarkResponse::questionId, BookmarkResponse::originalQuestionText)
                .containsExactly(tuple("q_4r", "프로젝트 경험을 말씀해 주세요."), tuple("q_4", null));
    }

    /** 꼬리질문의 꼬리질문도 직전 꼬리질문이 아니라 토픽의 주질문을 붙입니다. */
    @Test
    void 꼬리질문에는_같은_토픽의_주질문_문구를_붙인다() {
        givenAskedInOrder(
                asked(SESSION_ID, "q_1", QuestionType.QUESTION, 1, "지원 동기를 말씀해 주세요."),
                asked(SESSION_ID, "q_2", QuestionType.FOLLOWUP, 1, "왜 백엔드인가요?"),
                asked(SESSION_ID, "q_3", QuestionType.FOLLOWUP, 1, "그 경험은 언제였나요?"),
                asked(SESSION_ID, "q_4", QuestionType.QUESTION, 2, "프로젝트 경험을 말씀해 주세요."),
                asked(SESSION_ID, "q_5", QuestionType.FOLLOWUP, 2, "왜 그 방식으로 해결했나요?"));
        givenBookmarks(bookmarkOf("q_3", QuestionType.FOLLOWUP, 1), bookmarkOf("q_5", QuestionType.FOLLOWUP, 2));

        assertThat(service.list(USER_ID, 0, 20).bookmarks())
                .extracting(BookmarkResponse::questionId, BookmarkResponse::originalQuestionText)
                .containsExactly(tuple("q_3", "지원 동기를 말씀해 주세요."), tuple("q_5", "프로젝트 경험을 말씀해 주세요."));
    }

    /** 대체 주질문이 같은 토픽 번호로 올 수 있어, 그 꼬리질문 직전에 나간 주질문을 붙입니다. */
    @Test
    void 같은_토픽에_주질문이_둘이면_꼬리질문_직전의_주질문을_붙인다() {
        givenAskedInOrder(
                asked(SESSION_ID, "q_1", QuestionType.QUESTION, 1, "처음 주질문"),
                asked(SESSION_ID, "q_2", QuestionType.QUESTION, 1, "대체 주질문"),
                asked(SESSION_ID, "q_3", QuestionType.FOLLOWUP, 1, "꼬리질문"));
        givenBookmarks(bookmarkOf("q_3", QuestionType.FOLLOWUP, 1));

        assertThat(service.list(USER_ID, 0, 20).bookmarks())
                .extracting(BookmarkResponse::originalQuestionText)
                .containsExactly("대체 주질문");
    }

    @Test
    void 주질문만_담았으면_원래_질문을_조회하지_않는다() {
        givenBookmarks(bookmarkOf(QUESTION_ID, QuestionType.QUESTION, 1));

        service.list(USER_ID, 0, 20);

        verify(questionRepository, never()).findAllBySessionIdInOrderByCreatedAtAsc(anyCollection());
    }

    /** 세션의 질문이 나간 순서대로 이것들이라고 둡니다. */
    private void givenAskedInOrder(Question... asked) {
        when(questionRepository.findAllBySessionIdInOrderByCreatedAtAsc(anyCollection())).thenReturn(List.of(asked));
    }

    private void givenBookmarks(BookmarkedQuestion... bookmarks) {
        when(bookmarkRepository.findPageOwned(eq(USER_ID), any())).thenReturn(new PageImpl<>(List.of(bookmarks)));
    }

    private static Question asked(String sessionId, String questionId, QuestionType type, int topicIndex, String text) {
        return Question.builder().sessionId(sessionId).questionId(questionId)
                .type(type).topicIndex(topicIndex).text(text).build();
    }

    /** SESSION_ID 세션의 질문을 담은 북마크. */
    private static BookmarkedQuestion bookmarkOf(String questionId, QuestionType type, int topicIndex) {
        InterviewSession session = InterviewSession.builder().sessionId(SESSION_ID).jobRole("백엔드").build();
        return bookmarked(Question.builder().sessionId(SESSION_ID).questionId(questionId).session(session)
                .type(type).topicIndex(topicIndex).text("질문").build());
    }

    @Test
    void 메모를_저장하고_저장된_값을_돌려준다() {
        BookmarkedQuestion bookmark = givenBookmark(null);

        BookmarkMemoResponse response = service.editMemo(USER_ID, SESSION_ID, QUESTION_ID, "STAR 구조로 다시 정리하기");

        assertThat(bookmark.getMemo()).isEqualTo("STAR 구조로 다시 정리하기");
        assertThat(response.memo()).isEqualTo("STAR 구조로 다시 정리하기");
    }

    /** 메모만 지우는 API 가 따로 없어서, 빈 값이 곧 지우기입니다. */
    @Test
    void 빈_값이면_메모를_지운다() {
        for (String blank : new String[]{null, "", "   "}) {
            BookmarkedQuestion bookmark = givenBookmark("예전 메모");

            BookmarkMemoResponse response = service.editMemo(USER_ID, SESSION_ID, QUESTION_ID, blank);

            assertThat(bookmark.getMemo()).as("memo=[%s]", blank).isNull();
            assertThat(response.memo()).isNull();
        }
    }

    /** memo 는 VARCHAR(500) 이라 넘기면 DB 가 잘라내는 게 아니라 500 이 납니다. */
    @Test
    void 메모는_500자까지_받는다() {
        BookmarkedQuestion bookmark = givenBookmark(null);

        service.editMemo(USER_ID, SESSION_ID, QUESTION_ID, "가".repeat(500));
        assertThat(bookmark.getMemo()).hasSize(500);

        assertErrorCode(() -> service.editMemo(USER_ID, SESSION_ID, QUESTION_ID, "가".repeat(501)),
                ErrorCode.INVALID_REQUEST);
        assertThat(bookmark.getMemo()).hasSize(500);
    }

    @Test
    void 질문은행에_없으면_메모도_BOOKMARK_NOT_FOUND() {
        when(bookmarkRepository.findOwned(USER_ID, SESSION_ID, QUESTION_ID)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.editMemo(USER_ID, SESSION_ID, QUESTION_ID, "메모"), ErrorCode.BOOKMARK_NOT_FOUND);
    }

    /** 끝난 면접의 질문도 질문은행에서는 뺄 수 있어야 합니다. 세션 상태를 보지 않습니다. */
    @Test
    void 질문은행에서_빼면_플래그도_끈다() {
        Question question = givenQuestion(true);
        when(bookmarkRepository.deleteOwned(USER_ID, SESSION_ID, QUESTION_ID)).thenReturn(1);

        service.delete(USER_ID, SESSION_ID, QUESTION_ID);

        verify(bookmarkRepository).deleteOwned(USER_ID, SESSION_ID, QUESTION_ID);
        assertThat(question.isBookmarked()).isFalse();
    }

    /** 남의 북마크를 지우려 했다면 그 질문의 플래그도 건드리면 안 됩니다. */
    @Test
    void 이미_지웠거나_남의_것이면_BOOKMARK_NOT_FOUND() {
        Question question = givenQuestion(true);
        when(bookmarkRepository.deleteOwned(USER_ID, SESSION_ID, QUESTION_ID)).thenReturn(0);

        assertErrorCode(() -> service.delete(USER_ID, SESSION_ID, QUESTION_ID), ErrorCode.BOOKMARK_NOT_FOUND);
        assertThat(question.isBookmarked()).isTrue();
    }

    private BookmarkedQuestion givenBookmark(String memo) {
        BookmarkedQuestion bookmark = BookmarkedQuestion.builder().memo(memo).build();
        when(bookmarkRepository.findOwned(USER_ID, SESSION_ID, QUESTION_ID)).thenReturn(Optional.of(bookmark));
        return bookmark;
    }

    private static BookmarkedQuestion bookmarked(Question question) {
        return BookmarkedQuestion.builder().question(question).build();
    }

    private void givenSession(SessionStatus status) {
        InterviewSession session = InterviewSession.builder().sessionId(SESSION_ID).status(status).build();
        when(sessionRepository.findBySessionIdAndUser_UserId(SESSION_ID, USER_ID)).thenReturn(Optional.of(session));
    }

    private Question givenQuestion(boolean bookmarked) {
        Question question = Question.builder().sessionId(SESSION_ID).questionId(QUESTION_ID)
                .type(QuestionType.QUESTION).text("가장 기억에 남는 프로젝트 경험을 말씀해 주세요.")
                .bookmarked(bookmarked).build();
        when(questionRepository.findBySessionIdAndQuestionId(SESSION_ID, QUESTION_ID))
                .thenReturn(Optional.of(question));
        return question;
    }

    /** 거절되면 행도 건드리지 않아야 합니다. */
    private void assertToggleRejected(ThrowingCallable call, ErrorCode expected) {
        assertErrorCode(call, expected);
        verify(bookmarkRepository, never()).insertIfAbsent(anyString(), anyString(), anyString());
        verify(bookmarkRepository, never()).deleteOwned(anyString(), anyString(), anyString());
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
