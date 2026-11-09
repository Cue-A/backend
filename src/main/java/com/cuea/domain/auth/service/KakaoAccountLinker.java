package com.cuea.domain.auth.service;

import com.cuea.domain.user.dto.response.UserResponse;
import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.entity.UserAuth;
import com.cuea.domain.user.repository.UserAuthRepository;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 카카오에서 받은 사용자 정보로 이미 연결된 계정을 찾거나, 없으면 새 계정을 만들어 카카오를 연결합니다.
 * 같은 이메일의 기존 계정에는 연결하지 않습니다(#139).
 *
 * <p>{@link AuthService#loginWithKakao} 가 카카오 서버 호출(외부 HTTP)을 끝낸
 * 뒤에만 불러야 합니다. 외부 호출과 DB 트랜잭션을 분리하려고 별도 빈으로
 * 뺐습니다 — 같은 클래스 안에서 {@code this.linkOrCreate(...)} 로 불렀다면
 * 프록시를 거치지 않아 {@code @Transactional} 이 적용되지 않습니다.
 * {@code docs/01-conventions.md} 의 트랜잭션 항목 참고.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class KakaoAccountLinker {

    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;

    @Transactional
    KakaoLinkResult linkOrCreate(OAuthUserInfo info) {
        Optional<UserAuth> linked =
                userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, info.providerId());

        User user;
        boolean isNewUser;
        if (linked.isPresent()) {
            user = linked.get().getUser();
            isNewUser = false;
            // 가입 당시(비즈앱 전환 전 등)엔 이메일 동의를 못 받았다가, 이후 재로그인에서
            // 검증된 이메일이 생긴 경우를 채웁니다. 다른 사용자가 이미 쓰는 이메일이면
            // users.email UNIQUE 위반이라 건너뜁니다 — 그 경우는 매우 드물고, 값을
            // 강제로 덮어쓰기보다 null 로 남겨두는 쪽이 안전합니다.
            if (info.hasVerifiedEmail() && user.getEmail() == null
                    && !userRepository.existsByEmail(info.email())) {
                user.fillEmailIfAbsent(info.email());
            }
        } else {
            // 이메일이 같아도 기존 계정에 붙이지 않고 새 계정을 만듭니다(#139). 카카오 이메일은 검증됐어도
            // 기존 계정의 이메일은 소유가 검증된 적이 없습니다(이메일 가입 · 변경에 인증 메일이 없음).
            // 이메일 주인이 아닌 사람이 만든 계정에 카카오가 붙으면, 그 뒤 올린 이력서 · 면접 기록이 그 계정
            // 비밀번호를 아는 사람에게 보입니다. 그 이메일을 다른 계정이 쓰고 있으면 users.email UNIQUE
            // 때문에 새 계정의 이메일은 비웁니다.
            String nickname = info.nickname() != null
                    ? info.nickname()
                    : User.fallbackNickname(UUID.randomUUID().toString());
            boolean canUseEmail = info.hasVerifiedEmail() && !userRepository.existsByEmail(info.email());
            user = User.create(canUseEmail ? info.email() : null, nickname);
            isNewUser = true;
            user.link(Provider.KAKAO, info.providerId(), null);
            userRepository.save(user);
        }

        log.info("카카오 로그인 userId={} isNewUser={}", user.getUserId(), isNewUser);
        // 트랜잭션 안에서 응답을 만들어 둡니다. 밖에서는 auths 를 읽을 수 없습니다.
        return new KakaoLinkResult(UserResponse.from(user), isNewUser);
    }
}
