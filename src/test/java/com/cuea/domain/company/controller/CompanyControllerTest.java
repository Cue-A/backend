package com.cuea.domain.company.controller;

import com.cuea.common.result.Result;
import com.cuea.domain.company.dto.response.CompanyResponse;
import com.cuea.domain.company.service.CompanyQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨트롤러가 서비스에 위임하고 {@code Result<T>} 로 감싸는지 Spring 컨텍스트 없이
 * 직접 호출로 확인합니다(이 저장소는 {@code @WebMvcTest} 를 쓰지 않습니다).
 *
 * <p>로그인 가드({@code @CurrentUser}) 적용 여부는 {@code EndpointAuthGuardTest}
 * 가 classpath 스캔으로 따로 검증합니다.
 */
class CompanyControllerTest {

    @Test
    void 서비스_결과를_Result_로_감싸_돌려준다() {
        CompanyQueryService service = mock(CompanyQueryService.class);
        List<CompanyResponse> companies = List.of(
                new CompanyResponse(1L, "SK하이닉스"),
                new CompanyResponse(2L, "현대건설(주)"));
        when(service.list()).thenReturn(companies);

        CompanyController controller = new CompanyController(service);
        Result<List<CompanyResponse>> result = controller.list("user-1");

        verify(service).list();
        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isNull();
        assertThat(result.data()).isSameAs(companies);
    }

    @Test
    void 기업이_없으면_빈_목록을_담은_성공_응답을_돌려준다() {
        CompanyQueryService service = mock(CompanyQueryService.class);
        when(service.list()).thenReturn(List.of());

        CompanyController controller = new CompanyController(service);
        Result<List<CompanyResponse>> result = controller.list("user-1");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEmpty();
    }
}
