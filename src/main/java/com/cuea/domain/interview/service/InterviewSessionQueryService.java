package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 세션을 다른 도메인이 읽어갈 때의 창구입니다.
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

    /** 같은 루트 안에서 먼저 시작한 세션이 앞 회차입니다. */
    private static final Comparator<InterviewSession> STARTED_FIRST = Comparator
            .comparing(InterviewSession::getCreatedAt)
            .thenComparing(InterviewSession::getSessionId);

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
     * 본인 세션 목록. 회사가 함께 로드됩니다.
     *
     * @param folderId null 이면 폴더를 보지 않습니다
     * @param keyword  회사명 · 직무에서 대소문자 없이 찾습니다. 비어 있으면 보지 않습니다
     */
    @Transactional(readOnly = true)
    public Page<InterviewSession> findRecords(String userId, Collection<SessionStatus> statuses,
                                              UUID folderId, String keyword, Pageable pageable) {
        return sessionRepository.findRecords(userId, statuses, folderId, likePattern(keyword), pageable);
    }

    /**
     * 세션별 회차. 1회차는 1, 재연습은 같은 루트에서 자기보다 먼저 시작한 재연습 수 + 2 입니다.
     * 중단된 재연습은 세지 않습니다.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> roundsOf(String userId, List<InterviewSession> sessions) {
        List<String> rootIds = sessions.stream()
                .filter(InterviewSession::isRetry)
                .map(InterviewSession::rootSessionId)
                .distinct()
                .toList();
        Map<String, List<InterviewSession>> retriesByRoot = rootIds.isEmpty() ? Map.of()
                : sessionRepository.findRetriesOf(userId, rootIds, SessionStatus.ABORTED).stream()
                        .collect(Collectors.groupingBy(InterviewSession::rootSessionId));
        return sessions.stream().collect(Collectors.toMap(InterviewSession::getSessionId,
                s -> s.isRetry() ? 2 + countStartedBefore(retriesByRoot, s) : 1));
    }

    private static int countStartedBefore(Map<String, List<InterviewSession>> retriesByRoot,
                                          InterviewSession session) {
        return (int) retriesByRoot.getOrDefault(session.rootSessionId(), List.of()).stream()
                .filter(r -> STARTED_FIRST.compare(r, session) < 0)
                .count();
    }

    /** {@code %} · {@code _} 는 글자 그대로 찾도록 {@code !} 로 이스케이프합니다. */
    private static String likePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "%";
        }
        String escaped = keyword.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    /**
     * 나간 순서대로. 되묻기 포함. 소유권 확인이 없어 <b>API 경계에서 쓰지 마세요.</b>
     */
    @Transactional(readOnly = true)
    public List<Question> findQuestionsInOrderForInternal(String sessionId) {
        return questionRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId);
    }
}
