package com.cuea.common.security;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256-ok";
    private static final String OTHER_SECRET = "another-secret-that-is-also-long-enough-32";
    private static final String USER_ID = "u_1";

    private final JwtProvider provider = providerWith(Duration.ofMinutes(30), Duration.ofDays(14));

    @Test
    void access_토큰에서_userId_를_꺼낸다() {
        String token = provider.createAccessToken(USER_ID);

        TokenPayload payload = provider.parse(token, TokenType.ACCESS);

        assertThat(payload.userId()).isEqualTo(USER_ID);
    }

    @Test
    void refresh_토큰은_매번_다른_jti_를_갖는다() {
        IssuedToken first = provider.createRefreshToken(USER_ID);
        IssuedToken second = provider.createRefreshToken(USER_ID);

        assertThat(first.jti()).isNotBlank().isNotEqualTo(second.jti());
        assertThat(provider.parse(first.token(), TokenType.REFRESH).jti()).isEqualTo(first.jti());
    }

    /**
     * 이 검증이 없으면 {@code Authorization: Bearer <refreshToken>} 이 그대로
     * 통과해 14일짜리 access token 이 됩니다. 둘 다 같은 키로 서명하기 때문입니다.
     */
    @Test
    void refresh_토큰을_access_자리에_쓸_수_없다() {
        String refreshToken = provider.createRefreshToken(USER_ID).token();

        assertThatThrownBy(() -> provider.parse(refreshToken, TokenType.ACCESS))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void access_토큰을_refresh_자리에_쓸_수_없다() {
        String accessToken = provider.createAccessToken(USER_ID);

        assertThatThrownBy(() -> provider.parse(accessToken, TokenType.REFRESH))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    }

    /** 프론트가 "재발급하자" 와 "로그인 화면" 을 나누려면 이 둘이 달라야 합니다. */
    @Test
    void 만료된_access_토큰은_INVALID_TOKEN_이_아니라_TOKEN_EXPIRED_다() {
        JwtProvider expiring = providerWith(Duration.ofSeconds(-1), Duration.ofDays(14));
        String token = expiring.createAccessToken(USER_ID);

        assertThatThrownBy(() -> expiring.parse(token, TokenType.ACCESS))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 만료된_refresh_토큰은_INVALID_REFRESH_TOKEN_이다() {
        JwtProvider expiring = providerWith(Duration.ofMinutes(30), Duration.ofSeconds(-1));
        String token = expiring.createRefreshToken(USER_ID).token();

        assertThatThrownBy(() -> expiring.parse(token, TokenType.REFRESH))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void 다른_키로_서명된_토큰은_거부한다() {
        JwtProvider attacker = new JwtProvider(
                new JwtProperties(OTHER_SECRET, Duration.ofMinutes(30), Duration.ofDays(14)));
        String forged = attacker.createAccessToken(USER_ID);

        assertThatThrownBy(() -> provider.parse(forged, TokenType.ACCESS))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void 변조된_토큰은_거부한다() {
        String token = provider.createAccessToken(USER_ID);
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThatThrownBy(() -> provider.parse(tampered, TokenType.ACCESS))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 토큰이_아닌_문자열은_거부한다() {
        assertThatThrownBy(() -> provider.parse("garbage", TokenType.ACCESS))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    private static JwtProvider providerWith(Duration access, Duration refresh) {
        return new JwtProvider(new JwtProperties(SECRET, access, refresh));
    }
}
