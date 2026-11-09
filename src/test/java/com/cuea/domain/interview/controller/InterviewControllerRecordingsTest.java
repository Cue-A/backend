package com.cuea.domain.interview.controller;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
import com.cuea.domain.interview.dto.response.InterviewRecordingsResponse;
import com.cuea.domain.interview.service.InterviewAnswerService;
import com.cuea.domain.interview.service.InterviewRecordingService;
import com.cuea.domain.interview.service.InterviewSessionOptionsService;
import com.cuea.domain.interview.service.InterviewStartService;
import com.cuea.domain.interview.service.ReplaySessionOptionsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewController#getRecordings} 의 위임 계약을 봅니다. (Issue #103)
 *
 * <p>{@code InterviewControllerReplayOptionsTest} 와 같이 컨텍스트를 띄우지 않고 컨트롤러 고유
 * 책임만 확인합니다. 인증은 {@code EndpointAuthGuardTest}, 예외 → HTTP 매핑은
 * {@code GlobalExceptionHandlerTest}, 재생목록 조립은 {@code InterviewRecordingServiceTest} 몫입니다.
 */
class InterviewControllerRecordingsTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "sess_1";

    private InterviewRecordingService interviewRecordingService;
    private InterviewController controller;

    @BeforeEach
    void setUp() {
        interviewRecordingService = mock(InterviewRecordingService.class);
        controller = new InterviewController(
                mock(InterviewStartService.class),
                mock(InterviewAnswerService.class),
                mock(ReplaySessionOptionsService.class),
                mock(InterviewSessionOptionsService.class),
                interviewRecordingService);
    }

    @Test
    void 경로의_세션ID_와_사용자ID_를_서비스에_그대로_넘기고_Result_로_감싼다() {
        InterviewRecordingsResponse dto = InterviewRecordingsResponse.of(SESSION_ID, List.of(
                new InterviewRecordingsResponse.Item("q_1", 1, "QUESTION", "지원 동기를 말씀해 주세요.",
                        "https://q", "https://a", null)));
        when(interviewRecordingService.getRecordings(USER_ID, SESSION_ID)).thenReturn(dto);

        Result<InterviewRecordingsResponse> result = controller.getRecordings(USER_ID, SESSION_ID);

        verify(interviewRecordingService).getRecordings(USER_ID, SESSION_ID);
        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isNull();
        assertThat(result.data()).isSameAs(dto);
    }

    @Test
    void 서비스의_409_예외를_가로채지_않고_그대로_전파한다() {
        when(interviewRecordingService.getRecordings(USER_ID, SESSION_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_COMPLETED));

        assertThatThrownBy(() -> controller.getRecordings(USER_ID, SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_COMPLETED);
    }
}
