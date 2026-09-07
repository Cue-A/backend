package com.cuea.infrastructure.redis;

import com.cuea.infrastructure.ai.AiProperties;
import com.cuea.infrastructure.ai.dto.AiCompany;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 회사 목록 캐시.
 *
 * <p>DB 에 저장하지 않습니다. 원본이 두 곳에 있으면 반드시 어긋납니다.
 * 거의 바뀌지 않으므로 Redis 에 1시간만 둡니다.
 */
@Component
@RequiredArgsConstructor
public class CompanyCache {

    private static final String KEY = "cache:companies";

    private final RedisService redisService;
    private final AiProperties aiProperties;

    public Optional<List<AiCompany>> find() {
        return redisService.getList(KEY, AiCompany.class);
    }

    public void put(List<AiCompany> companies) {
        redisService.set(KEY, companies, aiProperties.companyCacheTtl());
    }

    public void evict() {
        redisService.delete(KEY);
    }
}
