package com.cuea.common.security;

/**
 * 검증을 통과한 토큰에서 꺼낸 값.
 *
 * @param userId JWT 의 {@code sub}
 * @param jti    refresh token 의 고유 ID. access token 은 {@code null}
 */
public record TokenPayload(String userId, String jti) {
}
