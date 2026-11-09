package com.cuea.domain.growth.repository;

import com.cuea.domain.growth.entity.Goal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 목표. <b>소유자가 있는 엔티티라 {@code JpaRepository} 를 상속하지 않습니다.</b>
 * 조회는 항상 소유자({@code userId})와 함께 걸어 남의 목표가 열리지 않게 합니다
 * ({@code docs/01-conventions.md} 의 "소유자 있는 엔티티는 Repository 를 상속합니다").
 *
 * <p>주간 목표는 {@code goal_type=WEEKLY}, {@code target_metric=SESSION_COUNT},
 * 그리고 그 주 월요일(KST)을 {@code start_date} 로 하는 한 행으로 저장합니다(Issue #129).
 *
 * <h2>왜 {@code FOR UPDATE} 조회가 필요한가</h2>
 * {@code goal} 테이블에는 {@code (user_id, goal_type, start_date)} UNIQUE 제약이 <b>없습니다</b>
 * (V1 스키마 그대로, 추가 마이그레이션 없이 구현). 그래서 {@code ON CONFLICT} upsert 를 쓸 수
 * 없고, 같은 사용자가 PATCH 를 동시에 두 번 보내면 "조회→없음→둘 다 INSERT" 로 같은 주 목표가
 * 2행 생길 수 있습니다. 이를 막기 위해 upsert 트랜잭션에서 먼저 같은 주 행을
 * {@link #findThisWeekForUpdate}(비관적 쓰기 잠금)로 조회합니다. 행이 있으면 그 행을 잠그고,
 * 없으면 나중 요청이 먼저 INSERT 된 행을 보도록 트랜잭션을 직렬화하는 역할은 서비스가
 * (사용자 단위 직렬화로) 보완합니다. 상세는 {@code WeeklyGoalService} 참고.
 */
public interface GoalRepository extends Repository<Goal, Long> {

    /**
     * 이번 주 주간 목표 1건을 <b>비관적 쓰기 잠금({@code FOR UPDATE})</b>으로 조회합니다.
     * upsert 의 "읽고 → 없으면 INSERT, 있으면 UPDATE" 분기 앞에서 호출해, 같은 행을 동시에
     * 수정하는 트랜잭션이 직렬화되도록 합니다.
     *
     * <p>소유자 스코프: {@code userId}. 주 식별자: {@code startDate}(그 주 월요일 KST).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Goal g"
            + " where g.user.userId = :userId"
            + " and g.goalType = :goalType"
            + " and g.targetMetric = :targetMetric"
            + " and g.startDate = :startDate")
    Optional<Goal> findThisWeekForUpdate(@Param("userId") String userId,
                                         @Param("goalType") String goalType,
                                         @Param("targetMetric") String targetMetric,
                                         @Param("startDate") LocalDate startDate);

    /**
     * 잠금 없는 읽기 전용 조회. {@code GET /api/growth} 처럼 수정이 따라오지 않는 조회에 씁니다.
     * 소유자 스코프를 쿼리에 묶습니다.
     */
    @Query("select g from Goal g"
            + " where g.user.userId = :userId"
            + " and g.goalType = :goalType"
            + " and g.targetMetric = :targetMetric"
            + " and g.startDate = :startDate")
    Optional<Goal> findThisWeek(@Param("userId") String userId,
                                @Param("goalType") String goalType,
                                @Param("targetMetric") String targetMetric,
                                @Param("startDate") LocalDate startDate);

    /** upsert 의 INSERT 경로. 새 주간 목표 행을 저장합니다. */
    Goal save(Goal goal);
}
