package com.cuea.domain.auth.service;

import java.time.Duration;

/**
 * 살아있는 refresh token 의 {@code jti} 를 들고 있습니다.
 *
 * <p><b>토큰 원문은 저장하지 않습니다.</b> 저장소가 통째로 새어도 그것만으로는
 * 토큰을 만들 수 없습니다. 서명 키가 있어야 합니다.
 *
 * <p>인터페이스로 둔 이유는 회전 규칙을 Redis 없이 테스트하기 위해서입니다.
 * 이 저장소의 테스트는 DB·Redis 없이 돌아야 합니다.
 */
public interface RefreshTokenStore {

    /** 로그인 시 새 jti 를 등록합니다. 다기기 로그인을 허용하므로 기존 것을 지우지 않습니다. */
    void save(String userId, String jti, Duration ttl);

    /**
     * 구 jti 를 신 jti 로 바꿉니다.
     *
     * @return 회전에 성공하면 {@code true}. 구 jti 가 등록돼 있지 않으면
     *         {@code false} 이며, 이때 <b>해당 사용자의 모든 jti 가 폐기됩니다.</b>
     */
    boolean rotate(String userId, String oldJti, String newJti, Duration ttl);

    /** 로그아웃. 그 기기 하나만 끊습니다. */
    void revoke(String userId, String jti);
}
