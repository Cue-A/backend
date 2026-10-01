package com.cuea.infrastructure.redis;

import com.cuea.domain.report.service.ReportProgressStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code report:progress:{reportId}} 에 마지막 진행 단계를 담습니다.
 *
 * <p>Redis 오류는 여기서 삼킵니다. 조회는 stage 없이 응답하면 되고, 폴러는 계속 돌아야
 * 합니다. {@link ReportProgressStore} 참고.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisReportProgressStore implements ReportProgressStore {

    private static final String KEY_PREFIX = "report:progress:";

    private final RedisService redisService;

    @Override
    public void save(String reportId, ReportProgress progress, Duration ttl) {
        try {
            redisService.set(key(reportId), progress, ttl);
        } catch (RuntimeException e) {
            log.warn("리포트 진행 단계 저장 실패 reportId={}", reportId, e);
        }
    }

    @Override
    public Optional<ReportProgress> find(String reportId) {
        try {
            return redisService.get(key(reportId), ReportProgress.class);
        } catch (RuntimeException e) {
            log.warn("리포트 진행 단계 조회 실패 reportId={}", reportId, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String reportId) {
        try {
            redisService.delete(key(reportId));
        } catch (RuntimeException e) {
            // 남아도 TTL 로 사라지고, 조회는 PROCESSING 일 때만 읽어 새지 않습니다.
            log.warn("리포트 진행 단계 삭제 실패 reportId={}", reportId, e);
        }
    }

    private String key(String reportId) {
        return KEY_PREFIX + reportId;
    }
}
