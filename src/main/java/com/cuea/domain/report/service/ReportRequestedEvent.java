package com.cuea.domain.report.service;

import java.util.UUID;

/**
 * 리포트 행이 {@code PROCESSING} 으로 커밋됐다는 알림. {@link ReportPoller} 가 받아
 * 폴링을 시작합니다.
 *
 * <p>커밋 뒤에 받아야 합니다({@code AFTER_COMMIT}). 커밋 전에 폴링을 시작하면 백그라운드
 * 스레드가 아직 저장되지 않은 행을 찾지 못할 수 있습니다.
 */
public record ReportRequestedEvent(
        Long reportId,
        UUID reportPublicId,
        String sessionId,
        String aiTaskId,
        int attempt
) {
}
