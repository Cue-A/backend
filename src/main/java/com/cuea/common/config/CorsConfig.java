package com.cuea.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 전 세계 공개(*) 대신 프론트 도메인만 허용합니다. docs/20-storage.md 참고.
 * S3/MinIO 버킷 CORS 는 별개이며 createbuckets 서비스가 설정합니다.
 *
 * <p><b>{@code addCorsMappings} 가 아니라 필터로 답니다.</b> MVC 레벨 CORS 는
 * DispatcherServlet 안쪽에서만 동작합니다. {@code JwtAuthFilter} 와
 * {@code AiSecretFilter} 는 요청을 끊을 때 {@code chain.doFilter()} 를 타지 않고
 * 응답을 직접 쓰는데, 그 응답에는 CORS 헤더가 붙지 않습니다. 브라우저는 401 을
 * 읽지 못하고 CORS 에러로 처리합니다. 즉 <b>프론트가 {@code TOKEN_EXPIRED} 를 보고
 * 재발급을 거는 동작이 성립하지 않습니다.</b>
 *
 * <p>그래서 순서가 중요합니다. {@code HIGHEST_PRECEDENCE} 로 두어
 * {@code AiSecretFilter}(5)·{@code JwtAuthFilter}(10) 보다 먼저 돌게 합니다.
 *
 * <p>WebSocket 핸드셰이크는 여기가 아니라 {@code WebSocketConfig} 의
 * {@code setAllowedOrigins} 가 봅니다.
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private final CorsProperties corsProperties;

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3000L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
