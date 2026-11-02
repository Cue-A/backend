package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.InterviewSessionOptionsResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewSessionOptionsService} 의 조회 계약을 봅니다(Issue #99).
 *
 * <p>소유권 검증은 공용 {@link InterviewSessionQueryService#getOwnedSession} 에 위임하므로,
 * 여기서는 (1) 저장된 세션 옵션을 응답 DTO 로 정확히 변환하는지(소문자 스타일·타이머·숨김),
 * (2) 진행 중 세션도 조회되는지, (3) 과거 durationLimit=null 세션도 오류 없이 null 로
 * 내려가는지, (4) 조회 과정에서 소유권 조회 외의 다른 호출(AI 등)이 없는지 확인합니다.
 */
class InterviewSessionOptionsServiceTest {

    private static final String USER_ID = "user-1";

    private InterviewSessionQueryService sessionQueryService;
    private InterviewSessionOptionsService service;
    private User user;

    @BeforeEach
    void setUp() {
        sessionQueryService = mock(InterviewSessionQueryService.class);
        service = new InterviewSessionOptionsService(sessionQueryService);
        user = mock(User.class);
    }

    private InterviewSession session(String sessionId, Persona persona,
                                     boolean hideQuestionText, Integer durationLimit,
                                     SessionStatus status) {
        return InterviewSession.builder()
                .sessionId(sessionId).user(user)
                .document(mock(com.cuea.domain.document.entity.Document.class))
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(persona).hideQuestionText(hideQuestionText)
                .durationLimit(durationLimit).status(status).build();
    }

    @Test
    void 저장된_옵션을_그대로_반환하고_스타일은_소문자다() {
        InterviewSession saved = session("sess_1", Persona.PRESSURE, true, 90, SessionStatus.IN_PROGRESS);
        when(sessionQueryService.getOwnedSession("sess_1", USER_ID)).thenReturn(saved);

        InterviewSessionOptionsResponse res = service.getSessionOptions(USER_ID, "sess_1");

        assertThat(res.sessionId()).isEqualTo("sess_1");
        assertThat(res.hideQuestionText()).isTrue();
        assertThat(res.interviewerStyle()).isEqualTo("pressure");
        assertThat(res.answerTimeLimitSec()).isEqualTo(90);
    }

    @Test
    void 진행중_세션도_조회된다() {
        InterviewSession inProgress =
                session("sess_live", Persona.FRIENDLY, false, 60, SessionStatus.IN_PROGRESS);
        when(sessionQueryService.getOwnedSession("sess_live", USER_ID)).thenReturn(inProgress);

        InterviewSessionOptionsResponse res = service.getSessionOptions(USER_ID, "sess_live");

        assertThat(res.interviewerStyle()).isEqualTo("friendly");
        assertThat(res.answerTimeLimitSec()).isEqualTo(60);
        // 완료 상태 제약이 없다(replay-options 와 달리 409 를 내지 않는다).
    }

    @Test
    void 타이머_저장_이전_과거세션_durationLimit_null_도_오류없이_null_로_내려간다() {
        InterviewSession legacy =
                session("sess_old", Persona.FRIENDLY, false, null, SessionStatus.COMPLETED);
        when(sessionQueryService.getOwnedSession("sess_old", USER_ID)).thenReturn(legacy);

        InterviewSessionOptionsResponse res = service.getSessionOptions(USER_ID, "sess_old");

        assertThat(res.answerTimeLimitSec()).isNull();
    }

    @Test
    void 없는_또는_남의_세션이면_getOwnedSession_의_404를_그대로_전파한다() {
        when(sessionQueryService.getOwnedSession("missing", USER_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> service.getSessionOptions(USER_ID, "missing"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 조회는_소유권_조회만_호출하고_다른_호출은_하지_않는다() {
        when(sessionQueryService.getOwnedSession("sess_1", USER_ID))
                .thenReturn(session("sess_1", Persona.FRIENDLY, false, 90, SessionStatus.IN_PROGRESS));

        service.getSessionOptions(USER_ID, "sess_1");

        verify(sessionQueryService).getOwnedSession("sess_1", USER_ID);
        verifyNoMoreInteractions(sessionQueryService);
    }
}
