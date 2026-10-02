package com.cuea.domain.auth.service;

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
 * 카카오에서 받은 사용자 정보로 기존 계정을 찾아 연결하거나 새 계정을 만듭니다.
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
            if (info.linkable() && user.getEmail() == null
                    && !userRepository.existsByEmail(info.email())) {
                user.fillEmailIfAbsent(info.email());
            }
        } else {
            Optional<User> byEmail = info.linkable()
                    ? userRepository.findByEmail(info.email())
                    : Optional.empty();

            // 이메일이 일치해도 그 계정에 이미 다른 카카오 계정이 연결돼 있으면 붙일 수
            // 없습니다(uk_user_auth_user_provider 위반). 카카오 이메일은 나중에 바뀔 수
            // 있어서, 지금 검증된 이 이메일이 예전에 다른 카카오 계정이 쓰던 값과 같은
            // 상황이 생깁니다. 이 경우는 서로 다른 사람이므로 새 계정을 만듭니다.
            boolean alreadyLinkedToOtherKakao = byEmail.isPresent()
                    && userAuthRepository.findByUser_UserIdAndProvider(byEmail.get().getUserId(), Provider.KAKAO)
                            .isPresent();

            if (byEmail.isPresent() && !alreadyLinkedToOtherKakao) {
                user = byEmail.get();
                isNewUser = false;
            } else {
                String nickname = info.nickname() != null
                        ? info.nickname()
                        : User.fallbackNickname(UUID.randomUUID().toString());
                // alreadyLinkedToOtherKakao 면 info.email() 은 이미 byEmail 계정이 쓰고
                // 있어서, 그대로 넣으면 users.email unique 위반으로 500 이 납니다.
                boolean canLinkEmail = info.linkable() && !alreadyLinkedToOtherKakao;
                user = User.create(canLinkEmail ? info.email() : null, nickname);
                isNewUser = true;
            }
            user.link(Provider.KAKAO, info.providerId(), null);
            userRepository.save(user);
        }

        log.info("카카오 로그인 userId={} isNewUser={}", user.getUserId(), isNewUser);
        return new KakaoLinkResult(user, isNewUser);
    }
}
