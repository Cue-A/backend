package com.cuea.domain.auth.service;

import com.cuea.domain.user.dto.response.UserResponse;

/**
 * 엔티티 대신 {@link UserResponse} 를 담습니다. 이 결과는 트랜잭션 밖으로 나가는데,
 * {@code User} 를 그대로 내보내면 LAZY 인 {@code auths} 를 읽는 순간
 * {@code LazyInitializationException} 이 납니다.
 */
record KakaoLinkResult(UserResponse user, boolean isNewUser) {
}
