package com.cuea.domain.growth.badge;

import com.cuea.domain.growth.entity.Badge;
import com.cuea.domain.growth.repository.BadgeRepository;
import com.cuea.domain.growth.repository.UserBadgeRepository;
import com.cuea.domain.home.service.KoreaTime;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.report.service.ReportSummaryQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 배지 획득 조건을 판정하고 <b>멱등 지급</b>합니다(Issue #131).
 *
 * <h2>왜 조건 전체를 재평가하나</h2>
 * 세션/리포트 완료 이벤트 하나로 특정 배지만 주지 않고, 호출될 때마다 <b>5종 전체를 실제
 * 기록으로 재평가</b>합니다. 그래서 같은 메서드가 실시간 지급과 소급(백필)·누락 복구를 겸합니다.
 * 이벤트가 유실되거나(예: 배지 기능 배포 전 완료한 과거 사용자) 재시도로 여러 번 와도, 다음
 * 평가에서 조건이 참이면 지급되고 이미 있으면 {@code ON CONFLICT} 로 건너뜁니다.
 *
 * <h2>트랜잭션·동시성·실패 격리</h2>
 * <ul>
 *   <li>이벤트 리스너는 세션/리포트 완료가 <b>커밋된 뒤</b>({@code AFTER_COMMIT}) 이 서비스를
 *       부릅니다. 배지 지급 실패가 세션/리포트 완료를 롤백시키지 않습니다.</li>
 *   <li>지급은 {@code uk_user_badge} UNIQUE 를 이용한 {@code INSERT ... ON CONFLICT DO NOTHING}
 *       한 방이라, 동시에 두 이벤트가 들어와도 1행만 남습니다("SELECT 후 INSERT" 경쟁 없음).</li>
 *   <li>{@link #reconcile} 는 {@code REQUIRES_NEW} 로 자체 트랜잭션에서 돕니다. 조회
 *       (readOnly) 흐름에서 lazy 로 불려도 readOnly 트랜잭션과 충돌하지 않습니다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BadgeGrantService {

    private final BadgeRepository badgeRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final InterviewSessionQueryService sessionQueryService;
    private final ReportSummaryQueryService reportQueryService;
    private final Clock clock;

    /**
     * 세션 완료 계열 배지(FIRST_SESSION·STREAK_3·TEN_SESSIONS·PRESSURE_CLEAR)를 재평가·지급합니다.
     * RISING 은 점수가 필요하므로 포함하지 않습니다(리포트 완료에서 처리).
     *
     * <p>{@code sourceSessionId} 는 지급 계기가 된 세션입니다. 집계 배지라 특정 세션에 묶이지
     * 않지만, 어느 완료에서 터졌는지 추적용으로 FIRST_SESSION 류에만 넣고 집계성(STREAK_3 등)은
     * null 로 둡니다. 구분이 모호하면 null 이 안전합니다(세션 삭제 시 SET NULL).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSessionCompleted(String userId, String sourceSessionId) {
        grantSessionBadges(userId, sourceSessionId);
    }

    /**
     * 리포트 점수 확정 계열 배지(RISING)를 재평가·지급합니다. 세션 완료로 이미 다른 배지가
     * 처리됐더라도, 리포트가 비동기로 늦게 끝나는 경우 여기서 RISING 을 따로 봅니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReportScored(String userId) {
        grantRisingBadge(userId);
    }

    /**
     * 5종 전체를 재평가·지급합니다. 소급(백필)·누락 복구·조회 시 lazy reconciliation 의 진입점입니다.
     * 여러 번 호출해도 멱등이며, 다른 사용자 기록에 영향을 주지 않습니다(모두 userId 로 좁힘).
     *
     * @return 이번 호출로 <b>새로 지급한</b> 배지 수(이미 있던 것은 세지 않음)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reconcile(String userId) {
        return grantSessionBadges(userId, null) + grantRisingBadge(userId);
    }

    private int grantSessionBadges(String userId, String sourceSessionId) {
        long completed = sessionQueryService.countCompletedSessions(userId);
        int granted = 0;

        if (completed >= 1) {
            granted += grant(userId, BadgeCode.FIRST_SESSION, sourceSessionId);
        }
        if (completed >= 10) {
            granted += grant(userId, BadgeCode.TEN_SESSIONS, null);
        }
        if (sessionQueryService.hasCompletedPressureSession(userId)) {
            granted += grant(userId, BadgeCode.PRESSURE_CLEAR, sourceSessionId);
        }
        List<LocalDate> dates = sessionQueryService.findCompletedPracticeDatesInKst(userId).stream()
                .map(LocalDate::parse)
                .toList();
        if (BadgeConditionPolicy.hasThreeDayStreak(dates)) {
            granted += grant(userId, BadgeCode.STREAK_3, null);
        }
        return granted;
    }

    private int grantRisingBadge(String userId) {
        List<Integer> scores = reportQueryService.findValidScoresOldestFirst(userId);
        if (BadgeConditionPolicy.hasRisingStreak(scores)) {
            return grant(userId, BadgeCode.RISING, null);
        }
        return 0;
    }

    /**
     * 멱등 지급. badge_code 로 정의를 찾아 {@code ON CONFLICT DO NOTHING} 으로 넣습니다.
     * 시드되지 않은 배지(정의 없음)는 조용히 건너뜁니다(마이그레이션 적용 전 안전).
     *
     * @return 새로 넣었으면 1, 이미 있으면 0
     */
    private int grant(String userId, BadgeCode code, String sourceSessionId) {
        Badge badge = badgeRepository.findByBadgeCode(code.name()).orElse(null);
        if (badge == null) {
            log.warn("배지 정의가 없어 지급을 건너뜁니다 code={} (V4 시드 확인)", code);
            return 0;
        }
        OffsetDateTime earnedAt = OffsetDateTime.now(clock);
        int inserted = userBadgeRepository.insertIfAbsent(userId, badge.getBadgeId(), sourceSessionId, earnedAt);
        if (inserted > 0) {
            log.info("배지 지급 userId={} code={}", userId, code);
        }
        return inserted;
    }

    /** 현재 사용자가 이미 가진 배지 코드. 조회 서비스가 지급 후 상태를 다시 읽을 때 씁니다. */
    @Transactional(readOnly = true)
    public Set<String> earnedCodes(String userId) {
        return Set.copyOf(userBadgeRepository.findEarnedBadgeCodes(userId));
    }
}
