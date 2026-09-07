package com.cuea.domain.auth.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.security.IssuedToken;
import com.cuea.common.security.JwtProvider;
import com.cuea.common.security.TokenPayload;
import com.cuea.common.security.TokenType;
import com.cuea.domain.auth.dto.response.TokenResponse;
import com.cuea.domain.user.dto.response.UserResponse;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰 발급·회전·폐기. <b>로그인 방식과 무관한 공통 지점입니다.</b>
 *
 * <p>이메일 로그인이든 카카오 로그인이든, 사용자를 확인한 뒤
 * {@code tokenService.issue(user)} 한 줄만 부르면 됩니다. 토큰 로직을
 * 각 로그인 구현이 건드릴 일이 없습니다.
 *
 * <pre>{@code
 * // 팀원이 쓰는 방법
 * User user = ...;                      // 이메일 검증 또는 카카오 조회로 찾은 사용자
 * return Result.ok(tokenService.issue(user));
 * }</pre>
 *
 * <p><b>로그아웃은 access token 을 즉시 무효화하지 못합니다.</b> access 는 무상태라
 * 서명만 맞으면 통과합니다. 남은 수명(30분)까지는 유효하며, 그래서 만료를 짧게
 * 잡았습니다. 전 요청에 Redis 조회를 추가하는 블랙리스트는 두지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final UserRepository userRepository;

    /** 로그인 성공 후 호출합니다. */
    public TokenResponse issue(User user) {
        IssuedToken refresh = jwtProvider.createRefreshToken(user.getUserId());
        refreshTokenStore.save(user.getUserId(), refresh.jti(), jwtProvider.refreshTokenValidity());

        return TokenResponse.of(
                jwtProvider.createAccessToken(user.getUserId()),
                refresh.token(),
                jwtProvider.accessTokenValidity().toSeconds(),
                UserResponse.from(user));
    }

    /**
     * 재발급. 쓸 때마다 refresh token 도 함께 회전합니다.
     *
     * <p>이미 회전된 토큰이 다시 오면 탈취로 보고 그 사용자의 모든 기기를 끊습니다.
     * 정상 사용자에게는 재로그인 한 번이지만, 탈취범에게는 훔친 토큰이 무용지물이
     * 됩니다.
     *
     * <p><b>프론트는 재발급 요청을 하나로 직렬화해야 합니다.</b> 병렬 요청 두 개가
     * 동시에 401 을 받고 각자 재발급을 시도하면, 늦게 도착한 쪽이 이미 회전된
     * 토큰을 들고 와 재사용으로 판정됩니다.
     */
    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        TokenPayload payload = jwtProvider.parse(refreshToken, TokenType.REFRESH);

        IssuedToken rotated = jwtProvider.createRefreshToken(payload.userId());
        boolean ok = refreshTokenStore.rotate(payload.userId(), payload.jti(),
                rotated.jti(), jwtProvider.refreshTokenValidity());
        if (!ok) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSED);
        }

        User user = userRepository.findById(payload.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        return TokenResponse.of(
                jwtProvider.createAccessToken(user.getUserId()),
                rotated.token(),
                jwtProvider.accessTokenValidity().toSeconds(),
                UserResponse.from(user));
    }

    /**
     * 로그아웃. 넘어온 refresh token 하나만 끊습니다.
     *
     * <p>이미 못 쓰는 토큰이 와도 조용히 넘어갑니다. 로그아웃이 실패해서 사용자가
     * 로그인 상태로 남는 것이 더 나쁩니다.
     */
    public void logout(String refreshToken) {
        try {
            TokenPayload payload = jwtProvider.parse(refreshToken, TokenType.REFRESH);
            refreshTokenStore.revoke(payload.userId(), payload.jti());
        } catch (BusinessException e) {
            log.debug("이미 무효한 토큰으로 로그아웃 요청 code={}", e.getErrorCode());
        }
    }
}
