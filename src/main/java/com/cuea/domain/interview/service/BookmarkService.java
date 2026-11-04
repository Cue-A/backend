package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.BookmarkToggleResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.BookmarkedQuestionRepository;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
