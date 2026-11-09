package com.cuea.domain.growth.repository;

import com.cuea.domain.growth.entity.UserBadge;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 사용자 배지 획득 기록. 소유자가 있는 엔티티라 {@code JpaRepository} 를 상속하지 않습니다
 * (docs/01-conventions.md).
 *
 * <h2>지급은 원자적 멱등 INSERT</h2>
 * "SELECT 로 없는 것 확인 → INSERT" 는 동시에 두 이벤트가 들어오면 둘 다 빈 결과를 보고
 * 각각 INSERT 해 {@code uk_user_badge} 위반(또는 중복)이 납니다. 대신 PostgreSQL
 * {@code INSERT ... ON CONFLICT (user_id, badge_id) DO NOTHING} 한 방으로 처리해, 경쟁
 * 상태에서도 1행만 남고 두 번째 시도는 0행으로 조용히 끝납니다. UNIQUE 제약이 동시성
 * 방어의 핵심입니다.
 */
public interface UserBadgeRepository extends Repository<UserBadge, Long> {

    /**
     * 멱등 지급. 이미 있으면 0, 새로 넣었으면 1 을 돌려줍니다. {@code source_session_id} 는
     * 세션과 무관한 배지(STREAK_3·RISING·TEN_SESSIONS 등 집계 배지)면 null 입니다.
     *
     * <p>네이티브 쿼리로 {@code ON CONFLICT} 를 씁니다(JPQL 에는 없습니다). 사용자·배지
     * FK 는 ID 로 직접 넣습니다. {@code earned_at} 은 소급·실시간 모두 지급 시각으로 기록합니다
     * (과거 달성 시각 복원은 비용이 커 하지 않음 — Issue #131 미확정 정책 보고 대상).
     *
     * @return 실제로 새로 넣은 행 수(0 또는 1)
     */
    @Modifying
    @Query(value = """
            insert into user_badge (user_id, badge_id, source_session_id, earned_at)
            values (:userId, :badgeId, :sourceSessionId, :earnedAt)
            on conflict (user_id, badge_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId,
                       @Param("badgeId") Long badgeId,
                       @Param("sourceSessionId") String sourceSessionId,
                       @Param("earnedAt") OffsetDateTime earnedAt);

    /** 본인이 획득한 배지 코드 목록. 성장 관리 조회가 전체 정의와 맞춰 획득 여부를 가립니다. */
    @Query("""
            select ub.badge.badgeCode
              from UserBadge ub
             where ub.user.userId = :userId
            """)
    List<String> findEarnedBadgeCodes(@Param("userId") String userId);

    /** 본인의 (배지 코드, 획득 시각) 목록. 조회 응답의 {@code earnedAt} 에 씁니다. */
    @Query("""
            select ub.badge.badgeCode, ub.earnedAt
              from UserBadge ub
             where ub.user.userId = :userId
            """)
    List<Object[]> findEarnedWithTimes(@Param("userId") String userId);
}
