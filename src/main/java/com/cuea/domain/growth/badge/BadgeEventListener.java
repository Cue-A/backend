package com.cuea.domain.growth.badge;

import com.cuea.common.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 세션/리포트 완료 이벤트를 받아 배지 지급을 트리거합니다(Issue #131).
 *
 * <p><b>완료가 커밋된 뒤에 돕니다</b>({@code AFTER_COMMIT}). 커밋 전에 판정하면 방금 완료한
 * 세션/점수가 집계에서 빠지고, 완료 트랜잭션이 롤백되면 잘못 지급됩니다. {@code @Async} 라
 * 완료 처리 스레드를 잡아두지 않습니다(self-invocation 이 아니라 별도 빈이어야 프록시를 거칩니다
 * — {@code ReportPoller} 와 같은 규약).
 *
 * <p>여기서 던진 예외는 완료 흐름으로 가지 않습니다(이미 커밋됨, 별도 스레드). 배지 지급이
 * 실패해도 면접/리포트 완료는 영향받지 않고, 다음 평가(다른 완료 이벤트·조회 시 lazy
 * reconciliation)에서 복구됩니다. 그래서 "완료는 됐는데 배지는 영구 누락" 이 발생하지 않습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BadgeEventListener {

    private final BadgeGrantService badgeGrantService;

    @Async(AsyncConfig.BADGE_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionCompleted(SessionCompletedEvent event) {
        try {
            badgeGrantService.onSessionCompleted(event.userId(), event.sessionId());
        } catch (RuntimeException e) {
            // 지급 실패는 완료 흐름과 격리합니다. 다음 평가에서 복구되므로 로깅만 합니다.
            log.warn("세션 완료 배지 지급 실패 userId={} sessionId={}", event.userId(), event.sessionId(), e);
        }
    }

    @Async(AsyncConfig.BADGE_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReportScored(ReportScoredEvent event) {
        try {
            badgeGrantService.onReportScored(event.userId());
        } catch (RuntimeException e) {
            log.warn("리포트 점수 배지 지급 실패 userId={}", event.userId(), e);
        }
    }
}
