package com.cuea.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis 접근은 여기를 지납니다. 도메인 서비스에서 RedisTemplate 을 직접 쓰지 않습니다.
 *
 * <p>캐시가 깨졌으면(타입 불일치 등) 지우고 빈 값을 돌려줍니다. 캐시 때문에
 * 요청이 실패하면 안 됩니다. 호출한 쪽이 원본을 다시 가져오게 두는 편이 낫습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        return convert(key, () -> objectMapper.convertValue(value, type));
    }

    /**
     * 리스트 전용.
     *
     * <p>제네릭 타입은 직렬화 과정에서 지워지므로 {@code get(key, List.class)} 로 읽으면
     * 원소가 {@code LinkedHashMap} 으로 돌아옵니다. 꺼내 쓸 때 ClassCastException 이
     * 나므로 원소 타입을 명시해서 다시 만들어 줍니다.
     */
    public <T> Optional<List<T>> getList(String key, Class<T> elementType) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        return convert(key, () -> objectMapper.convertValue(value,
                objectMapper.getTypeFactory().constructCollectionType(List.class, elementType)));
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    private <T> Optional<T> convert(String key, java.util.function.Supplier<T> conversion) {
        try {
            return Optional.of(conversion.get());
        } catch (IllegalArgumentException e) {
            log.warn("캐시를 읽지 못해 버립니다 key={} reason={}", key, e.getMessage());
            delete(key);
            return Optional.empty();
        }
    }
}
