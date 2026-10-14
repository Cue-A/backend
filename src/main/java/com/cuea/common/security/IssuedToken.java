package com.cuea.common.security;

/**
 * 방금 발급한 refresh token.
 *
 * <p>{@code jti} 를 따로 돌려주는 이유는 저장소가 토큰 원문이 아니라 이 값만
 * 기록하기 때문입니다. Redis 가 통째로 새어도 그것만으로는 토큰을 만들 수 없습니다.
 */
public record IssuedToken(String token, String jti) {
}
