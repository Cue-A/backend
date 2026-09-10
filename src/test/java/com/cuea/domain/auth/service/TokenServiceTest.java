package com.cuea.domain.auth.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.security.JwtProperties;
import com.cuea.common.security.JwtProvider;
import com.cuea.domain.auth.dto.response.TokenResponse;
import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 회전 규칙을 Redis 없이 검증합니다. 저장소는 {@link FakeRefreshTokenStore} 입니다.
 */
class TokenServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256-ok";

    private FakeRefreshTokenStore store;
    private TokenService tokenService;
    private User user;

    @BeforeEach
    void setUp() {
        JwtProvider jwtProvider = new JwtProvider(
                new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14)));
        store = new FakeRefreshTokenStore();

        user = User.create("kim@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$10$encoded");

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(anyString())).thenReturn(Optional.of(user));

        tokenService = new TokenService(jwtProvider, store, userRepository);
    }

    @Test
    void 로그인하면_access_와_refresh_를_함께_준다() {
        TokenResponse response = tokenService.issue(user);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(Duration.ofMinutes(30).toSeconds());
        assertThat(response.user().userId()).isEqualTo(user.getUserId());
        assertThat(response.user().providers()).containsExactly(Provider.LOCAL);
    }

    @Test
    void 재발급하면_refresh_도_새것으로_바뀐다() {
        TokenResponse issued = tokenService.issue(user);

        TokenResponse refreshed = tokenService.refresh(issued.refreshToken());

        assertThat(refreshed.refreshToken()).isNotEqualTo(issued.refreshToken());
        assertThat(store.liveTokenCount(user.getUserId())).isEqualTo(1);
    }

    @Test
    void 한번_쓴_refresh_는_다시_못_쓴다() {
        TokenResponse issued = tokenService.issue(user);
        tokenService.refresh(issued.refreshToken());

        assertThatThrownBy(() -> tokenService.refresh(issued.refreshToken()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFRESH_TOKEN_REUSED);
    }

    /**
     * 탈취된 토큰이 재사용되면 그 사용자의 <b>모든 기기</b>를 끊습니다.
     * 정상 사용자에게는 재로그인 한 번이지만, 탈취범에게는 훔친 토큰이 무용지물이 됩니다.
     */
    @Test
    void 재사용이_탐지되면_다른_기기까지_전부_끊는다() {
        TokenResponse laptop = tokenService.issue(user);
        tokenService.issue(user);                       // 폰에서도 로그인
        assertThat(store.liveTokenCount(user.getUserId())).isEqualTo(2);

        tokenService.refresh(laptop.refreshToken());    // 노트북 정상 재발급
        assertThatThrownBy(() -> tokenService.refresh(laptop.refreshToken()))
                .isInstanceOf(BusinessException.class);

        assertThat(store.liveTokenCount(user.getUserId())).isZero();
    }

    @Test
    void 여러_기기에서_동시에_로그인할_수_있다() {
        TokenResponse laptop = tokenService.issue(user);
        TokenResponse phone = tokenService.issue(user);

        assertThat(store.liveTokenCount(user.getUserId())).isEqualTo(2);
        assertThat(tokenService.refresh(laptop.refreshToken()).accessToken()).isNotBlank();
        assertThat(tokenService.refresh(phone.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void 로그아웃은_그_기기만_끊는다() {
        TokenResponse laptop = tokenService.issue(user);
        TokenResponse phone = tokenService.issue(user);

        tokenService.logout(laptop.refreshToken());

        assertThat(store.liveTokenCount(user.getUserId())).isEqualTo(1);
        assertThat(tokenService.refresh(phone.refreshToken()).accessToken()).isNotBlank();
    }

    /** 로그아웃이 실패해서 사용자가 로그인 상태로 남는 것이 더 나쁩니다. */
    @Test
    void 이미_무효한_토큰으로_로그아웃해도_예외가_나지_않는다() {
        tokenService.logout("garbage");
        tokenService.logout("");
    }

    @Test
    void access_토큰으로는_재발급할_수_없다() {
        TokenResponse issued = tokenService.issue(user);

        assertThatThrownBy(() -> tokenService.refresh(issued.accessToken()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    }
}
