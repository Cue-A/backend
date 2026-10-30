package com.cuea.domain.interview.controller;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
import com.cuea.domain.interview.dto.response.ReplaySessionOptionsResponse;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.service.InterviewAnswerService;
import com.cuea.domain.interview.service.InterviewStartService;
import com.cuea.domain.interview.service.ReplaySessionOptionsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewController#getReplayOptions} 의 얇은 위임 계약을 봅니다.
 *
 * <p>이 저장소의 컨트롤러 테스트는 다른 컨트롤러와 마찬가지로 <b>Spring 컨텍스트·MockMvc 를
 * 띄우지 않습니다.</b> 저장소 규약상 테스트는 DB·Redis 없이 돌아야 하고(docs/01-conventions.md,
 * {@code build.gradle} 주석), 컨트롤러 횡단 관심사는 전용 가드 테스트가 나눠 맡습니다.
 * <ul>
 *   <li>인증(모든 핸들러가 {@code @CurrentUser} 또는 {@code @PublicApi}) →
 *       {@code EndpointAuthGuardTest}</li>
 *   <li>{@code @CurrentUser} 해석·토큰 없으면 {@code UNAUTHORIZED} →
 *       {@code CurrentUserArgumentResolverTest}, {@code JwtAuthFilterTest}</li>
 *   <li>{@code BusinessException} → HTTP 상태·{@code Result} 매핑(404/409 등) →
 *       {@code GlobalExceptionHandlerTest}</li>
 *   <li>응답 DTO 8필드 값·타입·연습모드 null → {@code ReplaySessionOptionsServiceTest}</li>
 * </ul>
 *
 * <p>그래서 여기서는 중복 없이 컨트롤러 고유 책임만 확인합니다. (1) 경로 변수와 사용자 ID 를
 * 서비스에 그대로 넘기는지, (2) 서비스 결과를 {@code Result.ok} 성공 래퍼로 감싸는지,
 * (3) 서비스 예외를 가로채지 않고 그대로 전파하는지(핸들러가 상태로 매핑).
 */
class InterviewControllerReplayOptionsTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "sess_2nd";

    private ReplaySessionOptionsService replaySessionOptionsService;
    private InterviewController controller;

    @BeforeEach
    void setUp() {
        replaySessionOptionsService = mock(ReplaySessionOptionsService.class);
        controller = new InterviewController(
                mock(InterviewStartService.class),
                mock(InterviewAnswerService.class),
                replaySessionOptionsService);
    }

    @Test
    void 경로의_세션ID_와_사용자ID_를_서비스에_그대로_넘기고_Result_로_감싼다() {
        UUID docPublicId = UUID.randomUUID();
        ReplaySessionOptionsResponse dto = new ReplaySessionOptionsResponse(
                "sess_root", docPublicId.toString(), 12L, "백엔드 개발", 6,
                Persona.PRESSURE, "홍길동 자소서", "현대엔지니어링");
        when(replaySessionOptionsService.getReplayOptions(USER_ID, SESSION_ID)).thenReturn(dto);

        Result<ReplaySessionOptionsResponse> result =
                controller.getReplayOptions(USER_ID, SESSION_ID);

        // 전달한 2회차 sessionId 와 userId 가 그대로 서비스로.
        verify(replaySessionOptionsService).getReplayOptions(USER_ID, SESSION_ID);
        // 성공 Result 래퍼(= HTTP 200 본문).
        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isNull();
        assertThat(result.message()).isNull();
        assertThat(result.data()).isSameAs(dto);
    }

    @Test
    void 연습모드_응답도_그대로_전달한다_companyId_companyName_null() {
        ReplaySessionOptionsResponse practice = new ReplaySessionOptionsResponse(
                "sess_root", UUID.randomUUID().toString(), null, "데이터 분석", 3,
                Persona.FRIENDLY, "직접작성 이력서", null);
        when(replaySessionOptionsService.getReplayOptions(USER_ID, "sess_root")).thenReturn(practice);

        Result<ReplaySessionOptionsResponse> result =
                controller.getReplayOptions(USER_ID, "sess_root");

        assertThat(result.success()).isTrue();
        assertThat(result.data().companyId()).isNull();
        assertThat(result.data().companyName()).isNull();
    }

    @Test
    void 없는_또는_남의_세션이면_서비스의_404_예외를_그대로_전파한다() {
        when(replaySessionOptionsService.getReplayOptions(USER_ID, "missing"))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 컨트롤러는 가로채지 않고 그대로 던진다(GlobalExceptionHandler 가 404 로 매핑).
        assertThatThrownBy(() -> controller.getReplayOptions(USER_ID, "missing"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 완료되지_않은_세션이면_서비스의_409_예외를_그대로_전파한다() {
        when(replaySessionOptionsService.getReplayOptions(USER_ID, "sess_in_progress"))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_COMPLETED));

        assertThatThrownBy(() -> controller.getReplayOptions(USER_ID, "sess_in_progress"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_COMPLETED);
    }
}
