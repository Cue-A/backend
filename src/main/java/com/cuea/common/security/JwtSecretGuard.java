package com.cuea.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 개발용 기본 시크릿이 배포 환경에 그대로 올라가는 것을 막습니다.
 *
 * <p>{@code application.yml} 의 기본값은 저장소에 공개돼 있습니다. 그대로 배포되면
 * <b>누구나 아무 사용자의 토큰을 위조할 수 있습니다.</b> 조용히 취약해지는 것보다
 * 앱이 안 뜨는 편이 낫습니다.
 *
 * <p>local 프로파일에서는 통과시킵니다. 로컬 개발까지 막을 이유는 없습니다.
 */
@Component
@RequiredArgsConstructor
public class JwtSecretGuard implements InitializingBean {

    /** application.yml 에 적힌 개발용 기본값. 바뀌면 여기도 같이 고치세요. */
    static final String DEV_DEFAULT_SECRET = "local-dev-only-secret-change-me-at-least-32-bytes";

    private static final String LOCAL_PROFILE = "local";

    private final JwtProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        if (isLocal() || !DEV_DEFAULT_SECRET.equals(properties.secret())) {
            return;
        }
        throw new IllegalStateException("""
                개발용 기본 JWT 시크릿이 그대로입니다. APP_JWT_SECRET 을 설정하세요.
                이 값은 저장소에 공개돼 있어 누구나 토큰을 위조할 수 있습니다.""");
    }

    private boolean isLocal() {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            return Arrays.asList(environment.getDefaultProfiles()).contains(LOCAL_PROFILE);
        }
        return Arrays.asList(active).contains(LOCAL_PROFILE);
    }
}
