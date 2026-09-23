package com.cuea.domain.auth.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.auth.dto.request.KakaoLoginRequest;
import com.cuea.domain.auth.dto.request.LoginRequest;
import com.cuea.domain.auth.dto.request.SignupRequest;
import com.cuea.domain.auth.dto.response.TokenResponse;
import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.entity.UserAuth;
import com.cuea.domain.user.repository.UserAuthRepository;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 이메일 회원가입·로그인, 카카오 로그인. 토큰 발급 자체는 전부
 * {@link TokenService#issue(User)} 에 맡깁니다 — 여기서 토큰 로직을 다시 만들지 않습니다.
 *
 * <p>계정 연동 규칙은 {@code docs/02-database.md} 대로입니다. 카카오 이메일이
 * 검증됐고 그 이메일로 가입한 계정이 있으면 그 계정에 연결하고, 이메일
 * 회원가입은 기존 계정에 절대 연결하지 않습니다(반대 방향은 계정 탈취 경로).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 8~64자, 영문·숫자 최소 1개씩. 코드·문서 어디에도 정책이 없어 임시로 정한 값입니다. */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");

    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final OAuthClient kakaoOAuthClient;

    @Transactional
    public TokenResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (!PASSWORD_PATTERN.matcher(request.password()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD_FORMAT);
        }

        User user = User.create(request.email(), request.nickname());
        user.link(Provider.LOCAL, null, passwordEncoder.encode(request.password()));
        userRepository.save(user);

        log.info("이메일 회원가입 userId={}", user.getUserId());
        return tokenService.issue(user);
    }

    /**
     * 존재하지 않는 이메일과 비밀번호 불일치를 같은 {@code INVALID_CREDENTIALS} 로
     * 처리합니다. 어느 쪽이 틀렸는지 알려주면 이메일 존재 여부가 새어나갑니다.
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        UserAuth localAuth = user.authOf(Provider.LOCAL)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), localAuth.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        return tokenService.issue(user);
    }

    @Transactional
    public TokenResponse loginWithKakao(KakaoLoginRequest request) {
        OAuthUserInfo info = kakaoOAuthClient.fetch(request.authorizationCode(), request.redirectUri());

        Optional<UserAuth> linked =
                userAuthRepository.findByProviderAndProviderId(Provider.KAKAO, info.providerId());

        User user;
        boolean isNewUser;
        if (linked.isPresent()) {
            user = linked.get().getUser();
            isNewUser = false;
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
                user = User.create(info.linkable() ? info.email() : null, nickname);
                isNewUser = true;
            }
            user.link(Provider.KAKAO, info.providerId(), null);
            userRepository.save(user);
        }

        log.info("카카오 로그인 userId={} isNewUser={}", user.getUserId(), isNewUser);

        TokenResponse issued = tokenService.issue(user);
        return TokenResponse.of(issued.accessToken(), issued.refreshToken(),
                issued.expiresIn(), issued.user(), isNewUser);
    }
}
