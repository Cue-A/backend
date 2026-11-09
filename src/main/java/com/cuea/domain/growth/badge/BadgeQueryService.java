package com.cuea.domain.growth.badge;

import com.cuea.domain.growth.dto.response.BadgeView;
import com.cuea.domain.growth.entity.Badge;
import com.cuea.domain.growth.repository.BadgeRepository;
import com.cuea.domain.growth.repository.UserBadgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 성장 관리 배지 목록을 조립합니다(Issue #131). 로그인 사용자 기준으로 <b>정의 5종 전체</b>를
 * 돌려주며, 획득한 배지에는 {@code earnedAt} 을, 미획득에는 null 을 담습니다.
 *
 * <h2>조회 시 누락 복구(lazy reconciliation)</h2>
 * 조회 직전 {@link BadgeGrantService#reconcile}(멱등)를 호출해, 이벤트 유실·소급(배지 기능
 * 배포 전 완료한 과거 사용자) 때문에 빠진 배지를 그 자리에서 지급합니다. 조회 때마다 전체
 * 사용자를 스캔하지 않고 <b>해당 사용자</b> 기록만 평가하므로 비용이 작습니다. 지급은
 * {@code REQUIRES_NEW} 자체 트랜잭션이라, 이 메서드의 readOnly 트랜잭션과 충돌하지 않습니다
 * (readOnly 안에서 쓰기를 하지 않습니다).
 *
 * <p><b>복구(reconcile) 실패는 조회를 막지 않습니다.</b> 소급 지급 중 예외가 나도 성장 관리
 * 화면 전체(weeklyGoal 등 다른 조각 포함)를 500 으로 떨어뜨리지 않도록, 복구는 best-effort 로
 * 돌리고 실패는 WARN 으로 남긴 뒤 <b>이미 DB 에 지급된</b> 배지만으로 응답을 조립합니다. 실패를
 * 삼켜 숨기지 않고(로그로 드러냄), 다음 이벤트·다음 조회에서 다시 복구를 시도합니다.
 *
 * <h2>표시값</h2>
 * {@code badgeName} 은 DB 시드(프론트 목업 기준)에서, {@code tone} 은 {@link BadgeCode} 에서
 * 가져옵니다(badge 테이블에 tone 컬럼이 없음 — 시드 {@code condition_data.tone} 과 같은 값).
 * 순서는 {@link BadgeCode} 선언 순서로 고정해, 정의 일부가 없어도 5칸이 안정적으로 나옵니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BadgeQueryService {

    private final BadgeGrantService badgeGrantService;
    private final BadgeRepository badgeRepository;
    private final UserBadgeRepository userBadgeRepository;

    /**
     * 성장 관리 {@code badges[]} 에 넣을 5종 목록. 조회 전에 누락 배지를 복구(멱등 지급)합니다.
     *
     * @param userId 로그인 사용자. 다른 사용자 획득 내역은 노출되지 않습니다(모두 userId 로 좁힘)
     */
    public List<BadgeView> overview(String userId) {
        // 조회 전 누락 복구. 자체(REQUIRES_NEW) 트랜잭션에서 커밋되므로 아래 조회가
        // 방금 지급한 배지를 바로 읽습니다. 복구가 실패해도 조회는 이어가되 로그로 드러냅니다.
        try {
            badgeGrantService.reconcile(userId);
        } catch (RuntimeException e) {
            log.warn("배지 소급 지급(reconcile) 실패 — 기존 지급 기록만으로 조회를 이어갑니다 userId={}", userId, e);
        }

        Map<String, String> names = badgeRepository.findAll().stream()
                .collect(Collectors.toMap(Badge::getBadgeCode, Badge::getBadgeName, (a, b) -> a));

        Map<String, OffsetDateTime> earnedAt = userBadgeRepository.findEarnedWithTimes(userId).stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (OffsetDateTime) row[1],
                        (a, b) -> a));

        return Arrays.stream(BadgeCode.values())
                .map(code -> new BadgeView(
                        code.name(),
                        names.getOrDefault(code.name(), code.name()),
                        code.tone(),
                        earnedAt.get(code.name())))
                .toList();
    }
}
