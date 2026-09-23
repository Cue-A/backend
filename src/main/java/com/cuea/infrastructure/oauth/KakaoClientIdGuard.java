package com.cuea.infrastructure.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 개발용 더미 카카오 client-id 가 배포 환경에 그대로 올라가는 것을 막습니다.
 *
 * <p>{@link KakaoOAuthProperties#clientId()} 는 {@code @NotBlank} 만 걸려있어
 * 더미 문자열이 들어와도 검증을 통과합니다. {@code application.yml} 의 기본값은
 * 저장소에 공개돼 있으므로, non-local 프로파일에서 그대로면 카카오 로그인이
 * 조용히 실패하는 대신 앱을 아예 못 띄웁니다. {@code com.cuea.common.security.JwtSecretGuard}
 * 와 같은 패턴입니다.
 *
 * <p>local 프로파일에서는 통과시킵니다. 로컬 개발까지 막을 이유는 없습니다.
 */
@Component
@RequiredArgsConstructor
public class KakaoClientIdGuard implements InitializingBean {

    /** application.yml 에 적힌 개발용 기본값. 바뀌면 여기도 같이 고치세요. */
    static final String DEV_DEFAULT_CLIENT_ID = "local-dev-only-kakao-client-id";

    private static final String LOCAL_PROFILE = "local";

    private final KakaoOAuthProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        if (isLocal() || !DEV_DEFAULT_CLIENT_ID.equals(properties.clientId())) {
            return;
        }
        throw new IllegalStateException("""
                개발용 더미 카카오 client-id 가 그대로입니다. APP_OAUTH_KAKAO_CLIENT_ID 를 설정하세요.""");
    }

    private boolean isLocal() {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            return Arrays.asList(environment.getDefaultProfiles()).contains(LOCAL_PROFILE);
        }
        return Arrays.asList(active).contains(LOCAL_PROFILE);
    }
}
