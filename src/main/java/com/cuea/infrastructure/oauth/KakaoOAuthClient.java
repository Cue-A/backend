package com.cuea.infrastructure.oauth;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.auth.service.OAuthClient;
import com.cuea.domain.auth.service.OAuthUserInfo;
import com.cuea.domain.user.entity.Provider;
import com.cuea.infrastructure.oauth.dto.KakaoTokenResponse;
import com.cuea.infrastructure.oauth.dto.KakaoUserInfoResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * 카카오 인가 코드 → 액세스 토큰 → 사용자 정보 조회.
 *
 * <p>인가 코드 방식만 씁니다. 프론트가 카카오 access token 을 직접 받아 넘기는
 * 방식은 클라이언트 시크릿이 프론트로 나가고, 그 토큰이 정말 우리 앱에서 발급된
 * 것인지 검증할 책임이 우리에게 넘어와서 쓰지 않습니다. {@code docs/03-auth.md} 참고.
 *
 * <p>실패 원인(네트워크 오류 · 잘못된 코드 · 카카오 서버 오류)을 가리지 않고
 * {@code OAUTH_FAILED} 하나로 묶습니다. {@link OAuthClient#fetch} 의 계약이
 * 그렇게 정해져 있습니다 — 여기서 코드를 세분화하면 그 계약을 깨게 됩니다.
 */
@Slf4j
@Component
public class KakaoOAuthClient implements OAuthClient {

    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

    private final RestClient tokenClient;
    private final RestClient apiClient;
    private final KakaoOAuthProperties properties;

    public KakaoOAuthClient(KakaoOAuthProperties properties) {
        this.properties = properties;
        this.tokenClient = RestClient.builder()
                .baseUrl(TOKEN_URI)
                .defaultStatusHandler(status -> status.isError(), (req, res) -> {
                    log.warn("카카오 토큰 교환 실패 status={}", res.getStatusCode().value());
                    throw new BusinessException(ErrorCode.OAUTH_FAILED);
                })
                .build();
        this.apiClient = RestClient.builder()
                .baseUrl(USER_INFO_URI)
                .defaultStatusHandler(status -> status.isError(), (req, res) -> {
                    log.warn("카카오 사용자 정보 조회 실패 status={}", res.getStatusCode().value());
                    throw new BusinessException(ErrorCode.OAUTH_FAILED);
                })
                .build();
    }

    @Override
    public Provider provider() {
        return Provider.KAKAO;
    }

    @Override
    public OAuthUserInfo fetch(String code, String redirectUri) {
        String kakaoAccessToken = exchangeToken(code, redirectUri);
        return fetchUserInfo(kakaoAccessToken);
    }

    private String exchangeToken(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            form.add("client_secret", properties.clientSecret());
        }

        KakaoTokenResponse response = call(() -> tokenClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KakaoTokenResponse.class));

        if (response == null || response.accessToken() == null) {
            log.warn("카카오 토큰 응답에 access_token 이 없습니다");
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
        return response.accessToken();
    }

    private OAuthUserInfo fetchUserInfo(String kakaoAccessToken) {
        KakaoUserInfoResponse response = call(() -> apiClient.get()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
                .retrieve()
                .body(KakaoUserInfoResponse.class));

        if (response == null || response.id() == null) {
            log.warn("카카오 사용자 정보 응답이 비어 있습니다");
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }

        KakaoUserInfoResponse.KakaoAccount account = response.kakaoAccount();
        String email = account != null ? account.email() : null;
        boolean emailVerified = account != null && Boolean.TRUE.equals(account.isEmailVerified());
        String nickname = (account != null && account.profile() != null)
                ? account.profile().nickname()
                : null;

        return new OAuthUserInfo(Provider.KAKAO, String.valueOf(response.id()),
                email, emailVerified, nickname);
    }

    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (ResourceAccessException e) {
            log.error("카카오 서버에 연결하지 못했습니다", e);
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
    }
}
