package com.cuea.domain.auth.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.auth.dto.request.KakaoLoginRequest;
import com.cuea.domain.auth.dto.request.LoginRequest;
import com.cuea.domain.auth.dto.request.SignupRequest;
import com.cuea.domain.auth.dto.response.TokenResponse;
import com.cuea.domain.user.dto.response.UserResponse;
import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private TokenService tokenService;
    private OAuthClient kakaoOAuthClient;
    private KakaoAccountLinker kakaoAccountLinker;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenService = mock(TokenService.class);
        kakaoOAuthClient = mock(OAuthClient.class);
        kakaoAccountLinker = mock(KakaoAccountLinker.class);
        authService = new AuthService(userRepository, passwordEncoder, tokenService,
                kakaoOAuthClient, kakaoAccountLinker);

        when(tokenService.issue(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            return TokenResponse.of("access", "refresh", 1800L, UserResponse.from(user));
        });
        when(tokenService.issue(any(UserResponse.class))).thenAnswer(invocation ->
                TokenResponse.of("access", "refresh", 1800L, invocation.getArgument(0)));
    }

    @Test
    void 이미_가입된_이메일이면_거부한다() {
        when(userRepository.existsByEmail("kim@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(
                new SignupRequest("kim@example.com", "password1", "김취준")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    void 비밀번호_형식이_틀리면_별도_에러코드로_거부한다() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.signup(
                new SignupRequest("kim@example.com", "short", "김취준")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PASSWORD_FORMAT);
    }

    @Test
    void 회원가입하면_바로_토큰을_받는다() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$encoded");

        TokenResponse response = authService.signup(
                new SignupRequest("kim@example.com", "password1", "김취준"));

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.user().providers()).containsExactly(Provider.LOCAL);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void 존재하지_않는_이메일도_비밀번호_불일치와_같은_에러다() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "password1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 비밀번호가_틀리면_INVALID_CREDENTIALS_다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$encoded");
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("kim@example.com", "wrong")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    /** 카카오 전용 계정(비밀번호 없음)으로 이메일 로그인을 시도하는 경우입니다. */
    @Test
    void LOCAL_수단이_없는_계정은_이메일_로그인이_안된다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("kim@example.com", "password1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    /**
     * 카카오 계정 연동·생성 분기 자체는 {@link KakaoAccountLinkerTest} 에서 검증합니다.
     * 여기서는 {@code loginWithKakao} 가 카카오 호출 결과를 {@link KakaoAccountLinker}
     * 에 그대로 넘기고, 그 결과로 토큰을 발급하는 오케스트레이션만 확인합니다.
     */
    @Test
    void 카카오_로그인은_연동_결과를_그대로_토큰_발급에_넘긴다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(kakaoOAuthClient.fetch("code", "redirect"))
                .thenReturn(new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", true, "김취준"));
        when(kakaoAccountLinker.linkOrCreate(any(OAuthUserInfo.class)))
                .thenReturn(new KakaoLinkResult(UserResponse.from(user), true));

        TokenResponse response = authService.loginWithKakao(new KakaoLoginRequest("code", "redirect"));

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.user().email()).isEqualTo("kim@example.com");
        verify(tokenService).issue(UserResponse.from(user));
    }
}
