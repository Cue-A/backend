package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.DailyActivityRow;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** 사용자의 모든 세션 ID. 회원 탈퇴가 S3 미디어를 지울 때 씁니다. */
    @Transactional(readOnly = true)
    public List<String> getSessionIdsOf(String userId) {
        return sessionRepository.findSessionIdsByUserId(userId);
    }

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
     * 나지 않게 합니다.
     *
     * <p><b>요청 사용자 소유인 root 세션만 돌려줍니다.</b> source 세션 소유권은 호출 전에
     * 확인하지만, root 가 다른 세션일 수 있으므로 root 도 같은 {@code userId} 소유인지
     * 조회 조건으로 확인해 교차 사용자 참조를 막습니다. 없거나 남의 세션이면
     * {@code SESSION_NOT_FOUND}.
     */
    @Transactional(readOnly = true)
    public InterviewSession getRootForReplay(String rootSessionId, String userId) {
        return sessionRepository.findRootForReplay(rootSessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    /**
     * 재연습(시작·옵션 조회)의 공통 진입 단계입니다. 전달한 세션을 소유권·완료 상태로
     * 검증한 뒤, 그 묶음의 <b>최초(root) 세션</b>을 fetch join 으로 읽어 돌려줍니다.
     *
     * <ol>
     *   <li>전달 세션이 요청 사용자 소유인지({@code SESSION_NOT_FOUND}, 404). 없거나 남의
     *       세션이면 구별 없이 404.</li>
     *   <li>완료된 세션만 재연습 대상이므로 완료 상태인지({@code SESSION_NOT_COMPLETED}, 409).</li>
     *   <li>{@link InterviewSession#rootSessionId()} 로 최초 세션을 결정합니다(직전 회차가
     *       아니라 항상 최초).</li>
     *   <li>{@link #getRootForReplay} 로 root 를 읽습니다. root 소유권을 다시 확인하고
     *       ({@code SESSION_NOT_FOUND}), 옵션에 쓰는 LAZY 연관({@code document}·{@code company})을
     *       fetch join 으로 초기화해 트랜잭션 밖(open-in-view=false)에서도 안전하게 씁니다.</li>
     * </ol>
     *
     * <p><b>재연습 시작({@code InterviewStartService.resolveReplayPlan})과 옵션 조회
     * ({@code ReplaySessionOptionsService})가 이 메서드 하나를 공유합니다.</b> 1~3단계(소유권·
     * 완료 상태·root 결정)를 양쪽에서 복사하지 않아, 한쪽만 바뀌어 조회는 되는데 시작은
     * 실패하는 불일치를 구조적으로 막습니다(docs/12-replay.md "중복 구현하지 않습니다").
     * 반환한 root 세션의 {@code getSessionId()} 는 root 세션 ID 와 같습니다.
     */
    @Transactional(readOnly = true)
    public InterviewSession getReplayRoot(String sessionId, String userId) {
        InterviewSession source = getOwnedSession(sessionId, userId);

        if (!source.getStatus().isReportable()) {
            throw new BusinessException(ErrorCode.SESSION_NOT_COMPLETED,
                    "완료된 세션만 재연습할 수 있습니다");
        }

        return getRootForReplay(source.rootSessionId(), userId);
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
                s -> roundOf(s, retriesByRoot.getOrDefault(s.rootSessionId(), List.of()))));
    }

    /**
     * 본인 세션까지의 회차. 같은 루트의 1회차와 중단되지 않은 재연습 중, 이 세션보다 늦게 시작하지 않은
     * 것을 시작한 순서로 돌려줍니다. 번호는 {@link #roundsOf} 와 같습니다.
     *
     * <p>1회차는 상태와 상관없이 1 입니다. 회차 비교는 리포트가 끝난 회차만 고르므로 여기서는 거르지 않습니다.
     *
     * @throws BusinessException 없거나 남의 세션이면 {@code SESSION_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public List<SessionRound> roundsUpTo(String userId, String sessionId) {
        InterviewSession session = getOwnedSession(sessionId, userId);
        if (!session.isRetry()) {
            return List.of(new SessionRound(sessionId, 1, session.getCreatedAt()));
        }
        String rootId = session.rootSessionId();
        List<InterviewSession> retries =
                sessionRepository.findRetriesOf(userId, List.of(rootId), SessionStatus.ABORTED);
        Stream<InterviewSession> upToThis = retries.stream()
                .filter(r -> STARTED_FIRST.compare(r, session) <= 0)
                .sorted(STARTED_FIRST);
        return Stream.concat(sessionRepository.findBySessionIdAndUser_UserId(rootId, userId).stream(), upToThis)
                .map(s -> new SessionRound(s.getSessionId(), roundOf(s, retries), s.getCreatedAt()))
                .toList();
    }

    /** {@code retries} 는 그 루트의 중단되지 않은 재연습입니다. 회차 번호 규칙은 여기 하나뿐입니다. */
    private static int roundOf(InterviewSession session, List<InterviewSession> retries) {
        if (!session.isRetry()) {
            return 1;
        }
        return 2 + (int) retries.stream().filter(r -> STARTED_FIRST.compare(r, session) < 0).count();
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

    /**
     * 질문 ID → 원문. 되묻기 포함. 소유권 확인이 없어 <b>API 경계에서 쓰지 마세요.</b>
     * (session_id, question_id) 가 PK 라 ID 가 겹치지 않습니다.
     */
    @Transactional(readOnly = true)
    public Map<String, String> findQuestionTextsForInternal(String sessionId) {
        return questionRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .collect(Collectors.toMap(Question::getQuestionId, Question::getText));
    }

    /**
     * 홈 스트릭·이번 달 연습 일수용 창구(Issue #113). 본인의 완료 세션이 있는 KST 날짜를 중복 없이
     * 오름차순 {@code YYYY-MM-DD} 문자열로 돌려줍니다.
     *
     * <p>home 도메인이 {@link InterviewSessionRepository} 를 직접 쓰지 않도록 여기를 거칩니다
     * (도메인 간 참조는 service 레벨에서만 — docs/01-conventions.md).
     */
    @Transactional(readOnly = true)
    public List<String> findCompletedPracticeDatesInKst(String userId) {
        return sessionRepository.findCompletedDatesInKst(userId);
    }

    /**
     * FIRST_SESSION·TEN_SESSIONS 배지 판정 창구(Issue #131). 본인의 누적 완료 세션 수
     * (재연습 포함, {@code COMPLETED} 만). IN_PROGRESS·ABORTED 는 제외합니다.
     *
     * <p>growth(배지) 도메인이 {@link InterviewSessionRepository} 를 직접 쓰지 않도록 이 창구를
     * 거칩니다(도메인 간 참조는 service 레벨에서만 — docs/01-conventions.md).
     */
    @Transactional(readOnly = true)
    public long countCompletedSessions(String userId) {
        return sessionRepository.countByUser_UserIdAndStatusIn(userId, List.of(SessionStatus.COMPLETED));
    }

    /**
     * PRESSURE_CLEAR 배지 판정 창구(Issue #131). 본인에게 압박(PRESSURE) 페르소나로 완료한 세션이
     * 하나라도 있는지. 실제 저장된 {@code persona} + {@code COMPLETED} 상태 기준입니다. 모드 선택만
     * 하거나 중단(ABORTED)한 세션은 제외됩니다.
     */
    @Transactional(readOnly = true)
    public boolean hasCompletedPressureSession(String userId) {
        return sessionRepository.existsByUser_UserIdAndPersonaAndStatus(
                userId, Persona.PRESSURE, SessionStatus.COMPLETED);
    }

    /**
     * 면접 캘린더(Issue #130)의 {@code practicedDates} 창구. 반열림 구간 {@code [startInclusive,
     * endExclusive)}(KST 월 경계) 안에서 본인의 완료 세션이 있는 KST 날짜를 중복 없이 오름차순
     * {@code YYYY-MM-DD} 로 돌려줍니다.
     *
     * <p>calendar 도메인이 {@link InterviewSessionRepository} 를 직접 쓰지 않도록 여기를 거칩니다
     * (도메인 간 참조는 service 레벨에서만 — docs/01-conventions.md). 완료 세션에서 자동 계산하며
     * 사용자 등록 일정(calendar_event)과 무관합니다.
     */
    @Transactional(readOnly = true)
    public List<String> findCompletedPracticeDatesInKstBetween(String userId,
                                                               OffsetDateTime startInclusive,
                                                               OffsetDateTime endExclusive) {
        return sessionRepository.findCompletedDatesInKstBetween(userId, startInclusive, endExclusive);
    }

    /**
     * 홈 잔디(활동 히트맵)용 창구(Issue #114). 기간 안에서 KST 날짜별 완료 세션 수를 돌려줍니다.
     * 반열림 구간 {@code [startInclusive, endExclusive)}. 빈 날 0 채움은 호출부가 합니다.
     */
    @Transactional(readOnly = true)
    public List<DailyActivityRow> countDailyPracticeInKst(String userId,
                                                          OffsetDateTime startInclusive,
                                                          OffsetDateTime endExclusive) {
        return sessionRepository.countDailyActivityInKst(userId, startInclusive, endExclusive);
    }

    /**
     * 홈 "총 연습 횟수" 창구(Issue #114). 기간 내 완료 세션 수(재연습 포함). 반열림 구간 {@code [start, end)}.
     */
    @Transactional(readOnly = true)
    public long countCompletedPracticeInPeriod(String userId,
                                               OffsetDateTime startInclusive,
                                               OffsetDateTime endExclusive) {
        return sessionRepository.countCompletedInPeriod(userId, startInclusive, endExclusive);
    }
}
