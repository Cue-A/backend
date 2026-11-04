package com.cuea.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시간 의존성을 빈으로 뺍니다.
 *
 * <p>"오늘" 에 따라 결과가 갈리는 조회(홈 연습 통계·활동 집계, Issue #113·#114)는
 * {@code OffsetDateTime.now()} 를 직접 부르지 않고 이 {@link Clock} 을 주입받습니다.
 * 테스트가 {@code Clock.fixed(...)} 로 교체해 날짜 경계(자정·월초·KST/UTC 차이)를
 * 흔들림 없이 검증할 수 있습니다.
 *
 * <p>{@link ConditionalOnMissingBean} 이라 테스트가 자신의 {@code Clock} 을 등록하면
 * 그쪽이 우선합니다. 운영에서는 시스템 시계({@link Clock#systemDefaultZone()})입니다.
 * 존 변환은 사용하는 쪽({@code KoreaTime})에서 Asia/Seoul 로 맞춥니다.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
