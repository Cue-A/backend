package com.cuea.infrastructure.redis;

import com.cuea.domain.auth.service.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * {@code refresh:{userId}} 해시에 {@code jti -> 발급시각} 을 담습니다.
 *
 * <p>해시 하나로 세 가지가 다 됩니다 — 다기기 로그인(필드 여러 개), 로그아웃
 * (필드 하나 삭제), 재사용 탐지 시 전 기기 폐기(키 삭제).
 *
 * <p>TTL 은 키 전체에 걸립니다. 회전할 때마다 갱신되므로 계속 쓰는 사용자는
 * 다시 로그인하지 않습니다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> refreshRotateScript;

    @Override
    public void save(String userId, String jti, Duration ttl) {
        String key = key(userId);
        redisTemplate.opsForHash().put(key, jti, String.valueOf(Instant.now().getEpochSecond()));
        redisTemplate.expire(key, ttl);
    }

    @Override
    public boolean rotate(String userId, String oldJti, String newJti, Duration ttl) {
        Long result = redisTemplate.execute(
                refreshRotateScript,
                List.of(key(userId)),
                oldJti,
                newJti,
                String.valueOf(Instant.now().getEpochSecond()),
                String.valueOf(ttl.toSeconds()));

        boolean rotated = result != null && result == 1L;
        if (!rotated) {
            log.warn("등록되지 않은 refresh token. 해당 사용자의 모든 기기를 끊습니다 userId={}", userId);
        }
        return rotated;
    }

    @Override
    public void revoke(String userId, String jti) {
        redisTemplate.opsForHash().delete(key(userId), jti);
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
