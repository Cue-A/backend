package com.cuea.infrastructure.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoClientIdGuardTest {

    @Test
    void 배포_프로파일에서_개발용_더미_클라이언트id면_기동에_실패한다() {
        assertThatThrownBy(() -> guard(KakaoClientIdGuard.DEV_DEFAULT_CLIENT_ID, "prod").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_OAUTH_KAKAO_CLIENT_ID");
    }

    @Test
    void local_에서는_더미_클라이언트id를_통과시킨다() {
        assertThatCode(() -> guard(KakaoClientIdGuard.DEV_DEFAULT_CLIENT_ID, "local").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void 배포_프로파일이라도_클라이언트id를_바꿨으면_통과한다() {
        assertThatCode(() -> guard("a-real-kakao-client-id", "prod").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private KakaoClientIdGuard guard(String clientId, String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new KakaoClientIdGuard(
                new KakaoOAuthProperties(clientId, "", Duration.ofSeconds(5), Duration.ofSeconds(10)),
                environment);
    }
}
