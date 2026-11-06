package com.cuea.domain.growth.badge;

import com.cuea.domain.growth.dto.response.BadgeView;
import com.cuea.domain.growth.entity.Badge;
import com.cuea.domain.growth.repository.BadgeRepository;
import com.cuea.domain.growth.repository.UserBadgeRepository;
import lombok.RequiredArgsConstructor;
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
 * <h2>표시값</h2>
 * {@code badgeName} 은 DB 시드(프론트 목업 기준)에서, {@code tone} 은 {@link BadgeCode} 에서
 * 가져옵니다(badge 테이블에 tone 컬럼이 없음 — 시드 {@code condition_data.tone} 과 같은 값).
 * 순서는 {@link BadgeCode} 선언 순서로 고정해, 정의 일부가 없어도 5칸이 안정적으로 나옵니다.
 */
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
        // 방금 지급한 배지를 바로 읽습니다.
        badgeGrantService.reconcile(userId);

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
