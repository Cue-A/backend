package com.cuea.domain.interview.controller;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
import com.cuea.domain.interview.dto.response.InterviewSessionOptionsResponse;
import com.cuea.domain.interview.service.InterviewAnswerService;
import com.cuea.domain.interview.service.InterviewSessionOptionsService;
import com.cuea.domain.interview.service.InterviewStartService;
import com.cuea.domain.interview.service.ReplaySessionOptionsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewController#getSessionOptions} 의 얇은 위임 계약을 봅니다(Issue #99).
 *
 * <p>다른 면접 컨트롤러 테스트와 같이 Spring 컨텍스트·MockMvc 를 띄우지 않고 컨트롤러
 * 고유 책임만 확인합니다. 응답 DTO 값·소유권·404 매핑 등 세부는
 * {@code InterviewSessionOptionsServiceTest}·{@code GlobalExceptionHandlerTest} 가 맡습니다.
 * 여기서는 (1) 경로 변수·사용자 ID 를 서비스에 그대로 넘기는지, (2) 결과를 {@code Result.ok}
 * 로 감싸는지, (3) 서비스 예외를 가로채지 않고 전파하는지만 봅니다.
 */
class InterviewControllerSessionOptionsTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "sess_live";

    private InterviewSessionOptionsService interviewSessionOptionsService;
    private InterviewController controller;

    @BeforeEach
    void setUp() {
        interviewSessionOptionsService = mock(InterviewSessionOptionsService.class);
        controller = new InterviewController(
                mock(InterviewStartService.class),
                mock(InterviewAnswerService.class),
                mock(ReplaySessionOptionsService.class),
                interviewSessionOptionsService);
    }

    @Test
    void 경로의_세션ID_와_사용자ID_를_서비스에_그대로_넘기고_Result_로_감싼다() {
        InterviewSessionOptionsResponse dto =
                new InterviewSessionOptionsResponse(SESSION_ID, true, "pressure", 90);
        when(interviewSessionOptionsService.getSessionOptions(USER_ID, SESSION_ID)).thenReturn(dto);

        Result<InterviewSessionOptionsResponse> result =
                controller.getSessionOptions(USER_ID, SESSION_ID);

        verify(interviewSessionOptionsService).getSessionOptions(USER_ID, SESSION_ID);
        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isNull();
        assertThat(result.data()).isSameAs(dto);
    }

    @Test
    void 제한없음_세션은_answerTimeLimitSec_null_로_그대로_전달한다() {
        InterviewSessionOptionsResponse dto =
                new InterviewSessionOptionsResponse("sess_old", false, "friendly", null);
        when(interviewSessionOptionsService.getSessionOptions(USER_ID, "sess_old")).thenReturn(dto);

        Result<InterviewSessionOptionsResponse> result =
                controller.getSessionOptions(USER_ID, "sess_old");

        assertThat(result.data().answerTimeLimitSec()).isNull();
        assertThat(result.data().interviewerStyle()).isEqualTo("friendly");
    }

    @Test
    void 없는_또는_남의_세션이면_서비스의_404_예외를_그대로_전파한다() {
        when(interviewSessionOptionsService.getSessionOptions(USER_ID, "missing"))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> controller.getSessionOptions(USER_ID, "missing"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }
}
