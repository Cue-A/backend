package com.cuea.common.security;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 발급과 검증.
 *
 * <p>access 와 refresh 를 같은 키로 서명하므로 {@code typ} 클레임으로 구분합니다.
 * 검증할 때 기대하는 종류를 반드시 넘기세요. 안 그러면 refresh token 이
 * access token 자리에서 통과합니다.
 *
 * <p>실패 사유를 로그에 남길 때 <b>토큰 원문은 절대 남기지 않습니다.</b>
 * 로그가 그대로 유효한 자격증명이 되어버립니다.
 */
@Slf4j
@Component
public class JwtProvider {

    private static final String TYPE_CLAIM = "typ";

    private final SecretKey key;
    private final Duration accessValidity;
    private final Duration refreshValidity;

    public JwtProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessValidity = properties.accessTokenValidity();
        this.refreshValidity = properties.refreshTokenValidity();
    }

    public String createAccessToken(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim(TYPE_CLAIM, TokenType.ACCESS.claim())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessValidity)))
                .signWith(key)
                .compact();
    }

    public IssuedToken createRefreshToken(String userId) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(userId)
                .id(jti)
                .claim(TYPE_CLAIM, TokenType.REFRESH.claim())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshValidity)))
                .signWith(key)
                .compact();
        return new IssuedToken(token, jti);
    }

    /**
     * 서명·만료·종류를 확인하고 내용을 꺼냅니다.
     *
     * @throws BusinessException ACCESS 는 만료면 {@code TOKEN_EXPIRED}, 그 외
     *         {@code INVALID_TOKEN}. REFRESH 는 사유와 무관하게
     *         {@code INVALID_REFRESH_TOKEN} — 어느 쪽이든 프론트가 할 일은
     *         다시 로그인시키는 것 하나뿐이라 구분할 이유가 없습니다.
     */
    public TokenPayload parse(String token, TokenType expected) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!expected.claim().equals(claims.get(TYPE_CLAIM, String.class))) {
                log.debug("토큰 종류 불일치 expected={}", expected);
                throw fail(expected, false);
            }
            return new TokenPayload(claims.getSubject(), claims.getId());

        } catch (ExpiredJwtException e) {
            log.debug("토큰 만료 type={}", expected);
            throw fail(expected, true);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("토큰 검증 실패 type={} reason={}", expected, e.getClass().getSimpleName());
            throw fail(expected, false);
        }
    }

    private BusinessException fail(TokenType expected, boolean expired) {
        if (expected == TokenType.REFRESH) {
            return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return new BusinessException(expired ? ErrorCode.TOKEN_EXPIRED : ErrorCode.INVALID_TOKEN);
    }

    public Duration accessTokenValidity() {
        return accessValidity;
    }

    public Duration refreshTokenValidity() {
        return refreshValidity;
    }
}
