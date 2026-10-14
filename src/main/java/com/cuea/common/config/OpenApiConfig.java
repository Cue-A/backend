package com.cuea.common.config;

import com.cuea.common.security.CurrentUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI cueAOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Cue&A API")
                        .description("AI 면접 코칭 서비스 백엔드")
                        .version("v0"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    /**
     * {@code @CurrentUser} 는 {@link com.cuea.common.security.CurrentUserArgumentResolver} 가
     * 토큰에서 채우는 파라미터라 요청에 실려 오지 않는데, springdoc 은 이걸 몰라서
     * 필수 query 파라미터로 노출합니다. 그대로 두면 프론트가 {@code userId} 를 직접
     * 보내야 하는 것처럼 보입니다(서버는 이 값을 무시하고 토큰에서 꺼냅니다).
     */
    @Bean
    ParameterCustomizer hideCurrentUserParameter() {
        return (parameterModel, methodParameter) ->
                methodParameter.hasParameterAnnotation(CurrentUser.class) ? null : parameterModel;
    }
}
