package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.paging.PageRequests;
import com.cuea.domain.interview.dto.response.BookmarkListResponse;
import com.cuea.domain.interview.dto.response.BookmarkResponse;
import com.cuea.domain.interview.dto.response.BookmarkToggleResponse;
import com.cuea.domain.interview.entity.BookmarkedQuestion;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionId;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.BookmarkedQuestionRepository;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                BookmarkResponse.of(bookmark, originalTextOf(bookmark.getQuestion(), originals))));
    }

    /**
     * 페이지 안 되묻기들의 원래 질문 문구. 한 번에 조회하고 {@code (sessionId, questionId)} 쌍으로 맞춥니다.
     * 되묻기가 없으면 조회하지 않습니다.
     */
    private Map<QuestionId, String> originalTexts(List<BookmarkedQuestion> bookmarks) {
        List<Question> reasks = bookmarks.stream()
                .map(BookmarkedQuestion::getQuestion)
                .filter(question -> question.getReaskOf() != null)
                .toList();
        if (reasks.isEmpty()) {
            return Map.of();
        }
        Set<String> sessionIds = reasks.stream().map(Question::getSessionId).collect(Collectors.toSet());
        Set<String> questionIds = reasks.stream().map(Question::getReaskOf).collect(Collectors.toSet());
        return questionRepository.findAllBySessionIdInAndQuestionIdIn(sessionIds, questionIds).stream()
                .collect(Collectors.toMap(
                        question -> new QuestionId(question.getSessionId(), question.getQuestionId()),
                        Question::getText));
    }

    private static String originalTextOf(Question question, Map<QuestionId, String> originals) {
        return question.getReaskOf() == null
                ? null
                : originals.get(new QuestionId(question.getSessionId(), question.getReaskOf()));
    }

    /** 답변 제출과 같은 기준입니다. 중단은 SESSION_ABORTED, 그 밖에 진행 중이 아니면 SESSION_ENDED. */
    private void requireOwnedActiveSession(String userId, String sessionId) {
        InterviewSession session = sessionRepository.findBySessionIdAndUser_UserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (session.getStatus() == SessionStatus.ABORTED) {
            throw new BusinessException(ErrorCode.SESSION_ABORTED);
        }
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
    }
}
