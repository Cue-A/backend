package com.cuea.domain.company.controller;

import com.cuea.common.result.Result;
import com.cuea.domain.company.dto.response.CompanyInterestResponse;
import com.cuea.domain.company.service.CompanyInterestService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관심 기업 컨트롤러가 서비스에 위임하고 {@code Result<T>} 로 감싸는지 Spring 컨텍스트
 * 없이 직접 호출로 확인합니다(이 저장소는 {@code @WebMvcTest} 를 쓰지 않습니다).
 *
 * <p>로그인 가드({@code @CurrentUser}) 적용 여부는 {@code EndpointAuthGuardTest}
 * 가 classpath 스캔으로 따로 검증합니다.
 */
class CompanyInterestControllerTest {

    @Test
    void 목록을_서비스에_위임하고_Result_로_감싼다() {
        CompanyInterestService service = mock(CompanyInterestService.class);
        List<CompanyInterestResponse> interests = List.of(
                new CompanyInterestResponse(3L, "카카오"),
                new CompanyInterestResponse(1L, "SK하이닉스"));
        when(service.list("user-1")).thenReturn(interests);

        CompanyInterestController controller = new CompanyInterestController(service);
        Result<List<CompanyInterestResponse>> result = controller.list("user-1");

        verify(service).list("user-1");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isSameAs(interests);
    }

    @Test
    void 관심이_없으면_빈_목록_성공_응답() {
        CompanyInterestService service = mock(CompanyInterestService.class);
        when(service.list("user-1")).thenReturn(List.of());

        CompanyInterestController controller = new CompanyInterestController(service);
        Result<List<CompanyInterestResponse>> result = controller.list("user-1");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEmpty();
    }

    @Test
    void 등록을_서비스에_위임하고_성공_응답을_돌려준다() {
        CompanyInterestService service = mock(CompanyInterestService.class);

        CompanyInterestController controller = new CompanyInterestController(service);
        Result<Void> result = controller.register("user-1", 1L);

        verify(service).register("user-1", 1L);
        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void 해제를_서비스에_위임하고_성공_응답을_돌려준다() {
        CompanyInterestService service = mock(CompanyInterestService.class);

        CompanyInterestController controller = new CompanyInterestController(service);
        Result<Void> result = controller.remove("user-1", 1L);

        verify(service).remove("user-1", 1L);
        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isNull();
    }
}
