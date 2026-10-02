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
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * 이메일 회원가입·로그인, 카카오 로그인. 토큰 발급 자체는 전부
 * {@link TokenService#issue(User)} 에 맡깁니다 — 여기서 토큰 로직을 다시 만들지 않습니다.
 *
 * <p>카카오 계정 연동 규칙({@code docs/02-database.md})은 {@link KakaoAccountLinker}
 * 가 들고 있습니다 — 여기서는 외부 호출과 토큰 발급만 오케스트레이션합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 8~64자, 영문·숫자 최소 1개씩. 코드·문서 어디에도 정책이 없어 임시로 정한 값입니다. */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final OAuthClient kakaoOAuthClient;
    private final KakaoAccountLinker kakaoAccountLinker;

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

    /**
     * 카카오 서버 호출(외부 HTTP)은 트랜잭션 밖에서 먼저 끝내고, DB 조회·연결·저장은
     * {@link KakaoAccountLinker} 의 짧은 트랜잭션에 맡깁니다. 이 메서드 자체는
     * {@code @Transactional} 이 아닙니다 — {@code docs/01-conventions.md} 참고.
     */
    public TokenResponse loginWithKakao(KakaoLoginRequest request) {
        OAuthUserInfo info = kakaoOAuthClient.fetch(request.authorizationCode(), request.redirectUri());

        KakaoLinkResult result = kakaoAccountLinker.linkOrCreate(info);

        TokenResponse issued = tokenService.issue(result.user());
        return TokenResponse.of(issued.accessToken(), issued.refreshToken(),
                issued.expiresIn(), issued.user(), result.isNewUser());
    }
}
