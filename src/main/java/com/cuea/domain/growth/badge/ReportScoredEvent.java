package com.cuea.domain.growth.badge;

/**
 * 리포트 점수가 확정(COMPLETED·PARTIAL + score_total not null)돼 커밋됐다는 알림.
 * {@link BadgeEventListener} 가 받아 RISING 을 재평가·지급합니다.
 *
 * <p>리포트는 세션 완료 후 비동기로 늦게 생성됩니다. 세션 완료 이벤트만으로 RISING 을
 * 판정하면 점수가 아직 없어 영원히 누락될 수 있어, 점수 확정 시점에 별도 이벤트로 봅니다.
 *
 * <p>커밋 뒤에 받습니다({@code AFTER_COMMIT}). {@code ReportRequestedEvent} 와 같은 규약입니다.
 *
 * @param userId 리포트(세션) 소유자
 */
public record ReportScoredEvent(String userId) {
}
