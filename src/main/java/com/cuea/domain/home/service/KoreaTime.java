package com.cuea.domain.home.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 홈 통계·활동 집계의 "오늘" 기준을 한 곳에서 정합니다.
 *
 * <p>사용자에게 보이는 연습 일수·스트릭·잔디는 모두 <b>Asia/Seoul</b> 날짜 기준입니다
 * (요구사항 Issue #113·#114). DB 의 시각 컬럼은 {@code timestamptz} 라 저장 시점의 오프셋과
 * 무관하게 UTC 로 정규화되어 있고, 집계 쿼리에서 {@code AT TIME ZONE 'Asia/Seoul'} 로 KST
 * 날짜를 뽑습니다. 자바 쪽 "오늘" 도 같은 존으로 계산해야 경계(자정 전후)가 어긋나지 않습니다.
 *
 * <p>"오늘" 을 {@link Clock} 에서 얻으므로 테스트가 고정 시각을 주입해 날짜 경계를 안정적으로
 * 검증할 수 있습니다({@code Clock.fixed(...)}).
 */
public final class KoreaTime {

    /** 서비스가 날짜를 셈하는 기준 존. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private KoreaTime() {
    }

    /** 주입된 시계 기준 "오늘"(Asia/Seoul). */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
