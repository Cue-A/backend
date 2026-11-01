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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewSessionQueryService#getReplayRoot} 의 공통 단계 동작을 봅니다.
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
}
