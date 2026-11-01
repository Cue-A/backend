package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewSessionQueryService#getReplayRoot} 의 공통 단계 동작과 연습 기록 목록의
 * 회차 계산 · 검색어 처리, 회차 비교가 쓰는 회차 고르기를 봅니다.
 *
 * <p>{@code getReplayRoot} 는 재연습 시작({@code InterviewStartService})과 옵션 조회
 * ({@code ReplaySessionOptionsService})가 공유하는 단일 진입점입니다. 양쪽이 같은 단계
 * (소유권 → 완료 상태 → 최초 root 환원 → root fetch join 조회)를 복사하지 않도록 여기에
 * 모았으므로, 그 단계별 분기를 이 테스트가 한 곳에서 검증합니다.
 *
 * <ul>
 *   <li>없거나 남의 세션이면 {@code SESSION_NOT_FOUND}(404)</li>
 *   <li>완료되지 않은(IN_PROGRESS·ABORTED) 세션이면 {@code SESSION_NOT_COMPLETED}(409),
 *       이때 root 조회로 넘어가지 않는다</li>
 *   <li>2·3회차 ID 로 들어와도 {@code rootSessionId()} 로 환원해 최초 root 를 조회한다</li>
 *   <li>root 가 깨졌거나 남의 세션이면 {@code SESSION_NOT_FOUND}(404)</li>
 * </ul>
 *
 * <p>fetch join 초기화·소유자 WHERE 조건 같은 DB 쪽 동작은 실제 PostgreSQL 로 도는
 * {@code InterviewSessionReplayOptionsRepositoryTest} 가 봅니다(여기서는 리포지토리를 mock).
 */
class InterviewSessionQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-07-01T00:00:00Z");

    private InterviewSessionRepository sessionRepository;
    private InterviewSessionQueryService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        service = new InterviewSessionQueryService(sessionRepository, mock(QuestionRepository.class));
    }

    private InterviewSession session(String sessionId, InterviewSession retryOf, SessionStatus status) {
        return InterviewSession.builder()
                .sessionId(sessionId)
                .retryOfSession(retryOf)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(6)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(status)
                .build();
    }

    @Test
    void 최초_1회차면_자기_자신을_root_로_조회한다() {
        InterviewSession root = session("sess_root", null, SessionStatus.COMPLETED);
        when(sessionRepository.findBySessionIdAndUser_UserId("sess_root", USER_ID))
                .thenReturn(Optional.of(root));
        when(sessionRepository.findRootForReplay("sess_root", USER_ID))
                .thenReturn(Optional.of(root));

        InterviewSession result = service.getReplayRoot("sess_root", USER_ID);

        assertThat(result).isSameAs(root);
        verify(sessionRepository).findRootForReplay("sess_root", USER_ID);
    }

    @Test
    void 재연습_2회차_ID_로_들어와도_최초_root_로_환원해_조회한다() {
        InterviewSession root = session("sess_root", null, SessionStatus.COMPLETED);
        InterviewSession second = session("sess_2nd", root, SessionStatus.COMPLETED);
        when(sessionRepository.findBySessionIdAndUser_UserId("sess_2nd", USER_ID))
                .thenReturn(Optional.of(second));
        when(sessionRepository.findRootForReplay("sess_root", USER_ID))
                .thenReturn(Optional.of(root));

        InterviewSession result = service.getReplayRoot("sess_2nd", USER_ID);

        // 전달한 ID 는 2회차지만 root(sess_root)로 환원해 조회한다.
        verify(sessionRepository).findRootForReplay("sess_root", USER_ID);
        assertThat(result.getSessionId()).isEqualTo("sess_root");
    }

    @Test
    void 없거나_남의_세션이면_SESSION_NOT_FOUND_이고_root_조회로_넘어가지_않는다() {
        when(sessionRepository.findBySessionIdAndUser_UserId("missing", USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReplayRoot("missing", USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        verify(sessionRepository, never()).findRootForReplay(anyString(), anyString());
    }

    @Test
    void 완료되지_않은_IN_PROGRESS_세션이면_409_이고_root_조회로_넘어가지_않는다() {
        InterviewSession inProgress = session("sess_1", null, SessionStatus.IN_PROGRESS);
        when(sessionRepository.findBySessionIdAndUser_UserId("sess_1", USER_ID))
                .thenReturn(Optional.of(inProgress));

        assertThatThrownBy(() -> service.getReplayRoot("sess_1", USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_COMPLETED);

        verify(sessionRepository, never()).findRootForReplay(anyString(), anyString());
    }

    @Test
    void 완료되지_않은_ABORTED_세션이면_409_이고_root_조회로_넘어가지_않는다() {
        InterviewSession aborted = session("sess_1", null, SessionStatus.ABORTED);
        when(sessionRepository.findBySessionIdAndUser_UserId("sess_1", USER_ID))
                .thenReturn(Optional.of(aborted));

        assertThatThrownBy(() -> service.getReplayRoot("sess_1", USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_COMPLETED);

        verify(sessionRepository, never()).findRootForReplay(anyString(), anyString());
    }

    @Test
    void root_가_없거나_남의_세션이면_SESSION_NOT_FOUND() {
        // source(2회차)는 본인 소유로 조회되지만, root 가 깨졌거나 다른 사용자 소유라
        // userId 조건이 걸린 findRootForReplay 가 빈 값을 돌려주는 교차 참조 상황.
        InterviewSession root = session("sess_root", null, SessionStatus.COMPLETED);
        InterviewSession second = session("sess_2nd", root, SessionStatus.COMPLETED);
        when(sessionRepository.findBySessionIdAndUser_UserId("sess_2nd", USER_ID))
                .thenReturn(Optional.of(second));
        when(sessionRepository.findRootForReplay("sess_root", USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReplayRoot("sess_2nd", USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 일회차는_1_재연습은_먼저_시작한_재연습_수_더하기_2() {
        InterviewSession root = sessionAt("root", null, 0);
        InterviewSession second = sessionAt("second", root, 1);
        InterviewSession third = sessionAt("third", root, 2);
        when(sessionRepository.findRetriesOf(USER_ID, List.of("root"), SessionStatus.ABORTED))
                .thenReturn(List.of(second, third));

        Map<String, Integer> rounds = service.roundsOf(USER_ID, List.of(third, second, root));

        assertThat(rounds).containsEntry("root", 1).containsEntry("second", 2).containsEntry("third", 3);
    }

    /** 중단된 재연습은 조회에서 빠지므로 그 뒤 회차가 당겨집니다. */
    @Test
    void 중단된_재연습은_세지_않는다() {
        InterviewSession root = sessionAt("root", null, 0);
        InterviewSession afterAborted = sessionAt("after", root, 3);
        when(sessionRepository.findRetriesOf(USER_ID, List.of("root"), SessionStatus.ABORTED))
                .thenReturn(List.of(sessionAt("second", root, 1), afterAborted));

        assertThat(service.roundsOf(USER_ID, List.of(afterAborted))).containsEntry("after", 3);
    }

    /** 목록을 읽은 뒤 그 세션이 중단돼 회차 조회에서 빠져도 목록이 깨지지 않습니다. */
    @Test
    void 회차_조회에_없는_재연습도_앞선_재연습_수로_센다() {
        InterviewSession root = sessionAt("root", null, 0);
        InterviewSession justAborted = sessionAt("aborted", root, 5);
        when(sessionRepository.findRetriesOf(USER_ID, List.of("root"), SessionStatus.ABORTED))
                .thenReturn(List.of(sessionAt("second", root, 1)));

        assertThat(service.roundsOf(USER_ID, List.of(justAborted))).containsEntry("aborted", 3);
    }

    @Test
    void 재연습이_없으면_회차_조회를_하지_않는다() {
        assertThat(service.roundsOf(USER_ID, List.of(sessionAt("root", null, 0)))).containsEntry("root", 1);

        verify(sessionRepository, never()).findRetriesOf(anyString(), anyCollection(), any());
    }

    @Test
    void 이_세션까지의_회차는_1회차와_먼저_시작한_재연습을_시작_순서로_준다() {
        InterviewSession root = sessionAt("root", null, 0);
        InterviewSession second = sessionAt("second", root, 1);
        InterviewSession third = sessionAt("third", root, 2);
        given(root, third);
        when(sessionRepository.findRetriesOf(USER_ID, List.of("root"), SessionStatus.ABORTED))
                .thenReturn(List.of(third, second));

        List<SessionRound> rounds = service.roundsUpTo(USER_ID, "third");

        assertThat(rounds).containsExactly(
                new SessionRound("root", 1, T0),
                new SessionRound("second", 2, T0.plusMinutes(1)),
                new SessionRound("third", 3, T0.plusMinutes(2)));
    }

    /** 2회차 리포트에서 부르면 3회차는 비교 대상이 아닙니다. */
    @Test
    void 이_세션보다_늦게_시작한_재연습은_빠진다() {
        InterviewSession root = sessionAt("root", null, 0);
        InterviewSession second = sessionAt("second", root, 1);
        given(root, second);
        when(sessionRepository.findRetriesOf(USER_ID, List.of("root"), SessionStatus.ABORTED))
                .thenReturn(List.of(second, sessionAt("third", root, 2)));

        assertThat(service.roundsUpTo(USER_ID, "second")).extracting(SessionRound::sessionId)
                .containsExactly("root", "second");
    }

    /** 중단된 재연습은 조회에서 빠지므로 연습 기록 목록과 같은 번호가 됩니다. */
    @Test
    void 중단된_재연습_뒤의_회차는_당겨진_번호다() {
        InterviewSession root = sessionAt("root", null, 0);
        InterviewSession afterAborted = sessionAt("after", root, 3);
        given(root, afterAborted);
        when(sessionRepository.findRetriesOf(USER_ID, List.of("root"), SessionStatus.ABORTED))
                .thenReturn(List.of(sessionAt("second", root, 1), afterAborted));

        assertThat(service.roundsUpTo(USER_ID, "after")).extracting(SessionRound::round)
                .containsExactly(1, 2, 3);
    }

    @Test
    void 일회차면_자기_자신만_주고_재연습을_조회하지_않는다() {
        given(sessionAt("root", null, 0));

        assertThat(service.roundsUpTo(USER_ID, "root")).containsExactly(new SessionRound("root", 1, T0));
        verify(sessionRepository, never()).findRetriesOf(anyString(), anyCollection(), any());
    }

    @Test
    void 없거나_남의_세션의_회차는_SESSION_NOT_FOUND() {
        when(sessionRepository.findBySessionIdAndUser_UserId("other", USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.roundsUpTo(USER_ID, "other"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 검색어는_소문자로_바꾸고_특수문자를_이스케이프해_부분일치로_찾는다() {
        service.findRecords(USER_ID, List.of(SessionStatus.COMPLETED), null, "  QA 100%_! ", Pageable.unpaged());

        verify(sessionRepository).findRecords(eq(USER_ID), any(), eq(null), eq("%qa 100!%!_!!%"), any());
    }

    @Test
    void 검색어가_비어_있으면_모두_찾는다() {
        service.findRecords(USER_ID, List.of(SessionStatus.COMPLETED), null, " ", Pageable.unpaged());

        verify(sessionRepository).findRecords(eq(USER_ID), any(), eq(null), eq("%"), any());
    }

    private void given(InterviewSession... sessions) {
        for (InterviewSession session : sessions) {
            when(sessionRepository.findBySessionIdAndUser_UserId(session.getSessionId(), USER_ID))
                    .thenReturn(Optional.of(session));
        }
    }

    private static InterviewSession sessionAt(String id, InterviewSession root, int minutes) {
        return InterviewSession.builder().sessionId(id).retryOfSession(root)
                .status(SessionStatus.COMPLETED).createdAt(T0.plusMinutes(minutes)).build();
    }
}
