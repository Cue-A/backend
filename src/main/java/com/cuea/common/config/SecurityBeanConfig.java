package com.cuea.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해싱.
 *
 * <p>{@code spring-security-crypto} 만 씁니다. 인증 경로는 {@code JwtAuthFilter} 가
 * 담당하므로 {@code spring-boot-starter-security} 는 넣지 않습니다.
 * 이 의존만으로는 빈이 자동 등록되지 않아 여기서 직접 만듭니다.
 */
@Configuration
public class SecurityBeanConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
