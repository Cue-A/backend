package com.cuea.domain.auth.dto.response;

import com.cuea.domain.user.dto.response.UserResponse;

/**
 * 로그인 응답. <b>이메일·카카오·재발급 세 경로가 전부 이 형태를 반환합니다.</b>
 *
 * <p>{@code user} 를 같이 싣는 이유는 로그인 직후 프론트가 {@code /api/users/me} 를
 * 한 번 더 부르지 않게 하려는 것입니다. 나중에 추가하면 프론트 재작업입니다.
 *
 * <p>refresh token 을 본문으로 내려줍니다. httpOnly 쿠키가 XSS 관점에선 낫지만,
 * 배포에서 프론트·백 도메인이 갈리면 {@code SameSite=None; Secure} 가 필요해
 * HTTPS 가 붙기 전까지 로컬과 배포 동작이 갈립니다. 나중에 쿠키로 바꾸더라도
 * 이 형태는 그대로라 프론트 수정 범위는 인터셉터 한 곳입니다.
 *
 * @param expiresIn access token 남은 수명(초). 프론트가 선제 재발급에 씁니다
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user
) {

    private static final String BEARER = "Bearer";

    public static TokenResponse of(String accessToken, String refreshToken,
                                   long expiresIn, UserResponse user) {
        return new TokenResponse(accessToken, refreshToken, BEARER, expiresIn, user);
    }
}
