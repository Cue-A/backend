package com.cuea.infrastructure.oauth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 카카오 앱 설정. 카카오 인증 서버 주소(kauth.kakao.com 등)는 우리 쪽에서 바뀔 일이
 * 없는 고정 값이라 {@link KakaoOAuthClient} 안에 상수로 둡니다. 여기서는 앱마다
 * 달라지는 값만 관리합니다.
 *
 * @param clientSecret 카카오 콘솔에서 Client Secret 을 껐다면 비워둘 수 있습니다
 */
@Validated
@ConfigurationProperties(prefix = "app.oauth.kakao")
public record KakaoOAuthProperties(

        @NotBlank
        String clientId,

        String clientSecret
) {
}
