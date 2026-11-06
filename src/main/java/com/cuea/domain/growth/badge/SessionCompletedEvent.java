package com.cuea.domain.growth.badge;

/**
 * 세션이 {@code COMPLETED} 로 커밋됐다는 알림. {@link BadgeEventListener} 가 받아
 * 세션 완료 계열 배지(FIRST_SESSION·STREAK_3·TEN_SESSIONS·PRESSURE_CLEAR)를 재평가·지급합니다.
 *
 * <p>커밋 뒤에 받아야 합니다({@code AFTER_COMMIT}). 커밋 전에 판정하면 방금 완료한 세션이
 * 집계에서 빠질 수 있습니다. {@code ReportRequestedEvent} 와 같은 규약입니다.
 *
 * @param userId    세션 소유자
 * @param sessionId 완료된 세션. 지급 계기 추적용(source_session_id)
 */
public record SessionCompletedEvent(String userId, String sessionId) {
}
