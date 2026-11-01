package com.cuea.domain.auth.service;

import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserAuthRepository;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KakaoAccountLinkerTest {

    private UserRepository userRepository;
    private UserAuthRepository userAuthRepository;
    private KakaoAccountLinker kakaoAccountLinker;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userAuthRepository = mock(UserAuthRepository.class);
        kakaoAccountLinker = new KakaoAccountLinker(userRepository, userAuthRepository);

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
    }

    @Test
    void 이미_연결된_카카오_계정이면_로그인만_하고_새로_만들지_않는다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.of(user.authOf(Provider.KAKAO).orElseThrow()));

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", true, "김취준"));

        assertThat(result.isNewUser()).isFalse();
        verify(userRepository, never()).save(any(User.class));
    }

    /** 02-database.md 연동 규칙: 가입 당시 이메일 동의를 못 받았다가 이후 재로그인에서 채워지는 경우. */
    @Test
    void 이미_연결된_카카오_계정이고_이메일이_없으면_검증된_이메일을_채운다() {
        User user = User.create(null, "김취준");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.of(user.authOf(Provider.KAKAO).orElseThrow()));
        when(userRepository.existsByEmail("kim@example.com")).thenReturn(false);

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", true, "김취준"));

        assertThat(result.isNewUser()).isFalse();
        assertThat(result.user().email()).isEqualTo("kim@example.com");
    }

    /** 다른 사용자가 이미 그 이메일을 쓰고 있으면 users.email UNIQUE 위반을 피하려고 채우지 않는다. */
    @Test
    void 이미_연결된_카카오_계정이고_그_이메일을_다른_유저가_쓰면_채우지_않는다() {
        User user = User.create(null, "김취준");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.of(user.authOf(Provider.KAKAO).orElseThrow()));
        when(userRepository.existsByEmail("kim@example.com")).thenReturn(true);

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", true, "김취준"));

        assertThat(result.user().email()).isNull();
    }

    /** 이메일이 미검증이면 기존에 값이 있어도 채우기 로직 자체를 타지 않는다. */
    @Test
    void 이미_연결된_카카오_계정이고_이메일이_미검증이면_채우지_않는다() {
        User user = User.create(null, "김취준");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.of(user.authOf(Provider.KAKAO).orElseThrow()));

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", false, "김취준"));

        assertThat(result.user().email()).isNull();
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    void 검증된_이메일이_같은_기존_계정이_있으면_연결한다() {
        User existing = User.create("kim@example.com", "김취준");
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(existing));

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", true, "김취준"));

        assertThat(result.isNewUser()).isFalse();
        assertThat(existing.providers()).contains(Provider.KAKAO);
        assertThat(result.user().providers()).containsExactly(Provider.KAKAO);
        verify(userRepository).save(existing);
    }

    @Test
    void 이메일_미검증이면_기존_계정에_연결하지_않고_새로_만든다() {
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.empty());

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", false, "김취준"));

        assertThat(result.isNewUser()).isTrue();
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void 이메일_미검증이면_새_계정의_이메일은_저장하지_않는다() {
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.empty());

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", false, "김취준"));

        assertThat(result.isNewUser()).isTrue();
        assertThat(result.user().email()).isNull();
    }

    /**
     * 카카오 이메일은 나중에 바뀔 수 있어, "검증된 이메일이 같은 기존 계정"이 사실은
     * 이미 다른 카카오 계정과 연결돼 있을 수 있습니다. 그대로 연결하면
     * uk_user_auth_user_provider 위반으로 500이 나므로 새 계정을 만들어야 합니다.
     * 이때 새 계정에 그 이메일을 그대로 넣으면 이번엔 users.email unique 위반으로
     * 500이 나므로, 새 계정의 email은 비워야 합니다.
     */
    @Test
    void 이메일이_같아도_이미_다른_카카오_계정과_연결됐으면_새로_만든다() {
        User existing = User.create("kim@example.com", "김취준");
        existing.link(Provider.KAKAO, "kakao-old", null);
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-new"))
                .thenReturn(Optional.empty());
        when(userAuthRepository.findByUser_UserIdAndProvider(existing.getUserId(), Provider.KAKAO))
                .thenReturn(existing.authOf(Provider.KAKAO));
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(existing));

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-new", "kim@example.com", true, "김취준"));

        assertThat(result.isNewUser()).isTrue();
        assertThat(result.user().email()).isNull();
        verify(userRepository, never()).save(existing);
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u -> u != existing));
    }

    @Test
    void 신규_카카오_사용자면_계정을_새로_만든다() {
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-9"))
                .thenReturn(Optional.empty());

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-9", null, false, null));

        assertThat(result.isNewUser()).isTrue();
        assertThat(result.user().nickname()).startsWith("면접자");
        verify(userRepository).save(any(User.class));
    }
}
