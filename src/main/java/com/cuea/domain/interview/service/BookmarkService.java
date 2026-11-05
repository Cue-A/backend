package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.paging.PageRequests;
import com.cuea.domain.interview.dto.response.BookmarkListResponse;
import com.cuea.domain.interview.dto.response.BookmarkMemoResponse;
import com.cuea.domain.interview.dto.response.BookmarkResponse;
import com.cuea.domain.interview.dto.response.BookmarkToggleResponse;
import com.cuea.domain.interview.entity.BookmarkedQuestion;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionId;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.repository.BookmarkedQuestionRepository;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 질문은행. 면접 중에 질문을 담고 빼기와, 담은 질문의 목록 · 메모 · 삭제.
 *
 * <p><b>{@code bookmarked_question} 행과 {@code question.is_bookmarked} 를 한 트랜잭션에서 같이 바꿉니다.</b>
 * 둘이 어긋나면 목록과 상세가 다르게 보입니다.
 */
@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final InterviewSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final BookmarkedQuestionRepository bookmarkRepository;

    /**
     * 면접 중 북마크 버튼. 바뀔 상태를 받으므로 같은 요청이 두 번 와도 결과가 같습니다.
     * 되묻기도 담을 수 있습니다. 진행 중인 세션에서만 됩니다.
     *
     * <p>플래그는 행이 이미 있든 없든 요청한 값으로 맞춥니다. 어긋나 있었다면 여기서 바로잡힙니다.
     */
    @Transactional
    public BookmarkToggleResponse setBookmarked(String userId, String sessionId, String questionId, boolean bookmarked) {
        requireOwnedActiveSession(userId, sessionId);
        Question question = questionRepository.findBySessionIdAndQuestionId(sessionId, questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));

        if (bookmarked) {
            bookmarkRepository.insertIfAbsent(userId, sessionId, questionId);
        } else {
            bookmarkRepository.deleteOwned(userId, sessionId, questionId);
        }
        question.markBookmarked(bookmarked);
        return new BookmarkToggleResponse(bookmarked);
    }

    /** 질문은행 목록. 담은 시각 최신순이고 필터는 없습니다. 재연습에서 같은 질문을 또 담았으면 두 줄입니다. */
    @Transactional(readOnly = true)
    public BookmarkListResponse list(String userId, Integer page, Integer size) {
        Page<BookmarkedQuestion> bookmarks =
                bookmarkRepository.findPageOwned(userId, PageRequests.of(page, size, Sort.unsorted()));
        Map<QuestionId, String> originals = originalTexts(bookmarks.getContent());
        return BookmarkListResponse.from(bookmarks.map(bookmark ->
                BookmarkResponse.of(bookmark, originals.get(idOf(bookmark.getQuestion())))));
    }

    /** 처음 쓰기와 고치기가 같습니다. 담을 때 메모가 빈 행이 이미 생깁니다. 면접이 끝난 뒤에도 됩니다. */
    @Transactional
    public BookmarkMemoResponse editMemo(String userId, String sessionId, String questionId, String memo) {
        String normalized = BookmarkMemo.normalize(memo);
        BookmarkedQuestion bookmark = bookmarkRepository.findOwned(userId, sessionId, questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOOKMARK_NOT_FOUND));
        bookmark.editMemo(normalized);
        return new BookmarkMemoResponse(normalized);
    }

    /**
     * 질문은행에서 뺍니다. 메모도 함께 지워집니다. 세션 상태를 보지 않아 끝난 면접의 질문도 뺄 수 있습니다.
     *
     * <p>지운 행이 없으면(이미 지웠거나 남의 것) 404 이고 플래그도 건드리지 않습니다.
     */
    @Transactional
    public void delete(String userId, String sessionId, String questionId) {
        if (bookmarkRepository.deleteOwned(userId, sessionId, questionId) == 0) {
            throw new BusinessException(ErrorCode.BOOKMARK_NOT_FOUND);
        }
        questionRepository.findBySessionIdAndQuestionId(sessionId, questionId)
                .ifPresent(question -> question.markBookmarked(false));
    }

    /**
     * 페이지 안 되묻기 · 꼬리질문의 원래 질문 문구. 키는 담긴 질문 자신의 ID 이고, 주질문이면 값이 없습니다.
     *
     * <p>되묻기는 {@code reask_of} 가 가리키는 질문입니다. 꼬리질문에는 원래 질문을 가리키는 값이 없어, 같은
     * 세션 · 같은 토픽에서 그 꼬리질문 직전에 나간 주질문을 붙입니다. 대체 주질문이 같은 토픽 번호로 올 수 있어
     * 토픽 번호만으로는 하나로 정해지지 않기 때문입니다. 꼬리질문의 꼬리질문도 토픽의 주질문이 붙습니다.
     *
     * <p>{@code question_id} 가 세션 스코프라 키는 항상 {@code (sessionId, questionId)} 쌍입니다. 담긴 질문의
     * 세션들을 한 번에 조회하며, 되묻기 · 꼬리질문이 없으면 조회하지 않습니다.
     */
    private Map<QuestionId, String> originalTexts(List<BookmarkedQuestion> bookmarks) {
        Set<String> sessionIds = bookmarks.stream()
                .map(BookmarkedQuestion::getQuestion)
                .filter(question -> question.getType() != QuestionType.QUESTION)
                .map(Question::getSessionId)
                .collect(Collectors.toSet());
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<QuestionId, String> texts = new HashMap<>();
        Map<Topic, String> latestMain = new HashMap<>();
        Map<QuestionId, String> originals = new HashMap<>();
        for (Question asked : questionRepository.findAllBySessionIdInOrderByCreatedAtAsc(sessionIds)) {
            switch (asked.getType()) {
                case QUESTION -> latestMain.put(Topic.of(asked), asked.getText());
                case FOLLOWUP -> originals.put(idOf(asked), latestMain.get(Topic.of(asked)));
                case REASK -> originals.put(idOf(asked),
                        texts.get(new QuestionId(asked.getSessionId(), asked.getReaskOf())));
            }
            texts.put(idOf(asked), asked.getText());
        }
        return originals;
    }

    private static QuestionId idOf(Question question) {
        return new QuestionId(question.getSessionId(), question.getQuestionId());
    }

    /** 토픽 번호는 세션 안에서만 의미가 있습니다. */
    private record Topic(String sessionId, int topicIndex) {

        static Topic of(Question question) {
            return new Topic(question.getSessionId(), question.getTopicIndex());
        }
    }

    /** 답변 제출과 같은 상태 규칙({@link ActiveSession})입니다. */
    private void requireOwnedActiveSession(String userId, String sessionId) {
        ActiveSession.require(sessionRepository.findBySessionIdAndUser_UserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND)));
    }
}
