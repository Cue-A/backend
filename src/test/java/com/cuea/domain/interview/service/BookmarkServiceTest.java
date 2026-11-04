package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.BookmarkToggleResponse;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
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
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
        verify(bookmarkRepository, never()).insertIfAbsent(anyString(), anyString(), anyString());
        verify(bookmarkRepository, never()).deleteOwned(anyString(), anyString(), anyString());
    }
}
