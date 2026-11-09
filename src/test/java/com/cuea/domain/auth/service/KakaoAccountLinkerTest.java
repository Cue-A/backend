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

    /**
     * 기존 계정의 이메일은 소유가 검증된 적이 없습니다(이메일 가입 · 변경에 인증 메일이 없음). 이메일 주인이
     * 아닌 사람이 만든 계정일 수 있어, 카카오 이메일이 검증됐어도 그 계정에 붙이지 않습니다(#139).
     * 이메일은 기존 계정이 쓰고 있어 users.email UNIQUE 때문에 새 계정에는 넣지 않습니다.
     */
    @Test
    void 검증된_이메일이_같은_기존_계정이_있어도_연결하지_않고_새_계정을_만든다() {
        User existing = User.create("kim@example.com", "김취준");
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmail("kim@example.com")).thenReturn(true);

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", true, "김취준"));

        assertThat(result.isNewUser()).isTrue();
        assertThat(result.user().email()).isNull();
        assertThat(result.user().providers()).containsExactly(Provider.KAKAO);
        assertThat(existing.providers()).doesNotContain(Provider.KAKAO);
        verify(userRepository, never()).findByEmail(anyString());
        verify(userRepository, never()).save(existing);
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u -> u != existing));
    }

    @Test
    void 검증된_이메일을_쓰는_계정이_없으면_새_계정에_그_이메일을_넣는다() {
        when(userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-1"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmail("kim@example.com")).thenReturn(false);

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(
                new OAuthUserInfo(Provider.KAKAO, "kakao-1", "kim@example.com", true, "김취준"));

        assertThat(result.isNewUser()).isTrue();
        assertThat(result.user().email()).isEqualTo("kim@example.com");
        assertThat(result.user().nickname()).isEqualTo("김취준");
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
