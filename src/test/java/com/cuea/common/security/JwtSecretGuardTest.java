package com.cuea.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretGuardTest {

    @Test
    void 배포_프로파일에서_개발용_기본_시크릿이면_기동에_실패한다() {
        assertThatThrownBy(() -> guard(JwtSecretGuard.DEV_DEFAULT_SECRET, "prod").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void local_에서는_기본_시크릿을_통과시킨다() {
        assertThatCode(() -> guard(JwtSecretGuard.DEV_DEFAULT_SECRET, "local").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void 배포_프로파일이라도_시크릿을_바꿨으면_통과한다() {
        assertThatCode(() -> guard("a-real-secret-that-is-long-enough-32bytes", "prod").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private JwtSecretGuard guard(String secret, String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new JwtSecretGuard(
                new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofDays(14)),
                environment);
    }
}
