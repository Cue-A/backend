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
import com.cuea.domain.user.repository.UserAuthRepository;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
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
    private UserAuthRepository userAuthRepository;
    private PasswordEncoder passwordEncoder;
    private TokenService tokenService;
    private OAuthClient kakaoOAuthClient;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userAuthRepository = mock(UserAuthRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenService = mock(TokenService.class);
        kakaoOAuthClient = mock(OAuthClient.class);
        authService = new AuthService(userRepository, userAuthRepository,
                passwordEncoder, tokenService, kakaoOAuthClient);

        when(tokenService.issue(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            return TokenResponse.of("access", "refresh", 1800L, UserResponse.from(user));
        });
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

    @Test
    void 이미_연결된_카카오_계정이면_로그인만_하고_새로_만들지_않는다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.of(user.authOf(Provider.KAKAO).orElseThrow()));
        when(kakaoOAuthClient.fetch("code", "redirect"))
                .thenReturn(new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", true, "김취준"));

        TokenResponse response = authService.loginWithKakao(new KakaoLoginRequest("code", "redirect"));

        assertThat(response.isNewUser()).isFalse();
        verify(userRepository, org.mockito.Mockito.never()).save(any(User.class));
    }

    @Test
    void 검증된_이메일이_같은_기존_계정이_있으면_연결한다() {
        User existing = User.create("kim@example.com", "김취준");
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.empty());
        when(kakaoOAuthClient.fetch("code", "redirect"))
                .thenReturn(new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", true, "김취준"));
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(existing));

        TokenResponse response = authService.loginWithKakao(new KakaoLoginRequest("code", "redirect"));

        assertThat(response.isNewUser()).isFalse();
        assertThat(existing.providers()).contains(Provider.KAKAO);
        verify(userRepository).save(existing);
    }

    @Test
    void 이메일_미검증이면_기존_계정에_연결하지_않고_새로_만든다() {
        User existing = User.create("kim@example.com", "김취준");
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.empty());
        when(kakaoOAuthClient.fetch("code", "redirect"))
                .thenReturn(new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", false, "김취준"));

        TokenResponse response = authService.loginWithKakao(new KakaoLoginRequest("code", "redirect"));

        assertThat(response.isNewUser()).isTrue();
        verify(userRepository, org.mockito.Mockito.never()).findByEmail(anyString());
    }

    @Test
    void 이메일_미검증이면_새_계정의_이메일은_저장하지_않는다() {
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.empty());
        when(kakaoOAuthClient.fetch("code", "redirect"))
                .thenReturn(new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", false, "김취준"));

        TokenResponse response = authService.loginWithKakao(new KakaoLoginRequest("code", "redirect"));

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.user().email()).isNull();
    }

    /**
     * 카카오 이메일은 나중에 바뀔 수 있어, "검증된 이메일이 같은 기존 계정"이 사실은
     * 이미 다른 카카오 계정과 연결돼 있을 수 있습니다. 그대로 연결하면
     * uk_user_auth_user_provider 위반으로 500이 나므로 새 계정을 만들어야 합니다.
     */
    @Test
    void 이메일이_같아도_이미_다른_카카오_계정과_연결됐으면_새로_만든다() {
        User existing = User.create("kim@example.com", "김취준");
        existing.link(Provider.KAKAO, "kakao-old", null);
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-new"))
                .thenReturn(Optional.empty());
        when(userAuthRepository.findByUser_UserIdAndProvider(existing.getUserId(), Provider.KAKAO))
                .thenReturn(existing.authOf(Provider.KAKAO));
        when(kakaoOAuthClient.fetch("code", "redirect"))
                .thenReturn(new OAuthUserInfo(Provider.KAKAO, "kakao-new", "kim@example.com", true, "김취준"));
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(existing));

        TokenResponse response = authService.loginWithKakao(new KakaoLoginRequest("code", "redirect"));

        assertThat(response.isNewUser()).isTrue();
        verify(userRepository, org.mockito.Mockito.never()).save(existing);
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u -> u != existing));
    }

    @Test
    void 신규_카카오_사용자면_계정을_새로_만든다() {
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-9"))
                .thenReturn(Optional.empty());
        when(kakaoOAuthClient.fetch("code", "redirect"))
                .thenReturn(new OAuthUserInfo(Provider.KAKAO, "kakao-9", null, false, null));

        TokenResponse response = authService.loginWithKakao(new KakaoLoginRequest("code", "redirect"));

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.user().nickname()).startsWith("면접자");
        verify(userRepository).save(any(User.class));
    }
}
