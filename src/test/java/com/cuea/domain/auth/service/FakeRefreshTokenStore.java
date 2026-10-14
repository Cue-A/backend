package com.cuea.domain.auth.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link RedisRefreshTokenStore} 와 같은 규칙을 메모리로 흉내 냅니다.
 * 회전 규칙을 Redis 없이 검증하기 위한 것입니다.
 *
 * <p>Lua 스크립트의 분기와 이 클래스의 분기가 어긋나면 테스트가 통과해도
 * 실제로는 깨집니다. 스크립트를 고치면 여기도 같이 고치세요.
 */
class FakeRefreshTokenStore implements RefreshTokenStore {

    private final Map<String, Set<String>> jtisByUser = new HashMap<>();

    @Override
    public void save(String userId, String jti, Duration ttl) {
        jtisByUser.computeIfAbsent(userId, k -> new HashSet<>()).add(jti);
    }

    @Override
    public boolean rotate(String userId, String oldJti, String newJti, Duration ttl) {
        Set<String> jtis = jtisByUser.get(userId);
        if (jtis == null || !jtis.remove(oldJti)) {
            jtisByUser.remove(userId);   // 재사용 의심 → 전 기기 폐기
            return false;
        }
        jtis.add(newJti);
        return true;
    }

    @Override
    public void revoke(String userId, String jti) {
        Set<String> jtis = jtisByUser.get(userId);
        if (jtis != null) {
            jtis.remove(jti);
        }
    }

    int liveTokenCount(String userId) {
        return jtisByUser.getOrDefault(userId, Set.of()).size();
    }
}
