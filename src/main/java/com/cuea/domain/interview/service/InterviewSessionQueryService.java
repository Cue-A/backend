package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 끝난 세션을 다른 도메인이 읽어갈 때의 창구입니다.
 *
 * <p>도메인 간 참조는 서비스 레벨에서만 하므로, 리포트 도메인이 면접 리포지토리를
 * 직접 보지 않도록 여기를 거칩니다. docs/01-conventions.md 참고.
 *
 * <p>반환하는 엔티티의 LAZY 연관(회사 등)을 쓰려면 호출하는 쪽이 트랜잭션 안에
 * 있어야 합니다. {@code open-in-view} 가 꺼져 있습니다.
 */
@Service
@RequiredArgsConstructor
public class InterviewSessionQueryService {

    private final InterviewSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;

    /** 본인 세션. 없거나 남의 세션이면 구별 없이 404 입니다. */
    @Transactional(readOnly = true)
    public InterviewSession getOwnedSession(String sessionId, String userId) {
        return sessionRepository.findBySessionIdAndUser_UserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    /**
     * 소유권 확인 없이 세션을 읽습니다. <b>API 경계에서 쓰지 마세요.</b>
     * 이미 소유권을 확인한 세션을 백그라운드에서 다시 읽을 때만 씁니다.
     */
    @Transactional(readOnly = true)
    public InterviewSession getSessionForInternal(String sessionId) {
        return sessionRepository.findBySessionIdForInternal(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    /**
     * 재면접 옵션 비교용 root 세션을 읽습니다. 비교에 쓰는 LAZY 연관
     * ({@code document}·{@code company})을 fetch join 으로 미리 초기화해, 트랜잭션 밖
     * ({@code open-in-view=false})에서 비교해도 {@code LazyInitializationException} 이
     * 나지 않게 합니다. 소유권은 호출 전 source 세션에서 이미 확인한 뒤 씁니다.
     */
    @Transactional(readOnly = true)
    public InterviewSession getRootForReplay(String rootSessionId) {
        return sessionRepository.findRootForReplay(rootSessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    /**
     * 나간 순서대로. 되묻기 포함. 소유권 확인이 없어 <b>API 경계에서 쓰지 마세요.</b>
     */
    @Transactional(readOnly = true)
    public List<Question> findQuestionsInOrderForInternal(String sessionId) {
        return questionRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId);
    }
}
