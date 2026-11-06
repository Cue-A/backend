package com.cuea.domain.growth.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.growth.dto.response.WeeklyGoalResponse;
import com.cuea.domain.growth.entity.Goal;
import com.cuea.domain.growth.repository.GoalRepository;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이번 주 연습 목표(Issue #129). 목표는 {@code goal} 테이블에 주 단위 한 행으로 저장합니다
 * (마이그레이션 없이 V1 스키마 재사용). 매핑 규약:
 *
 * <pre>
 * goal_type     = WEEKLY
 * target_metric = SESSION_COUNT
 * target_value  = 1~7
 * start_date    = 그 주 월요일(KST)
 * end_date      = 그 주 일요일(KST)
 * status        = ACTIVE
 * </pre>
 *
 * <p>실적({@code doneCount})은 저장하지 않고 조회·저장 시마다 다시 셉니다. 홈 통계가 쓰는
 * {@link InterviewSessionQueryService#countCompletedPracticeInPeriod}(COMPLETED 만, 재연습 포함,
 * 반열림 UTC 구간)를 그대로 재사용합니다 — 도메인 간 참조는 service 레벨에서만
 * ({@code docs/01-conventions.md}). 전체 세션을 메모리로 끌어오지 않고 DB 가 집계합니다.
 *
 * <h2>동시성 · 중복 방지</h2>
 * {@code goal} 에는 {@code (user_id, goal_type, start_date)} UNIQUE 제약이 없어 {@code ON CONFLICT}
 * upsert 를 쓸 수 없습니다. 같은 사용자가 PATCH 를 동시에 두 번 보내면 두 트랜잭션이 모두
 * "조회→없음" 을 보고 둘 다 INSERT 해 같은 주 목표가 2행 생길 수 있습니다. 두 겹으로 막습니다.
 * <ol>
 *   <li><b>사용자 단위 직렬화</b>: {@code user_id} 별 자물쇠로 upsert 트랜잭션 <b>전체(커밋까지)</b>를
 *       감쌉니다. {@link TransactionTemplate} 로 커밋이 {@code synchronized} 블록 안에서 끝나게 해
 *       "조회→없으면 INSERT→커밋" 사이에 다른 트랜잭션이 끼지 못하게 합니다. (프록시 self-invocation
 *       으로 {@code @Transactional} 이 안 걸리는 함정을 피하려 템플릿을 씁니다.)</li>
 *   <li><b>{@code SELECT ... FOR UPDATE}</b>: 트랜잭션 안에서 같은 주 행을 비관적 쓰기 잠금으로 먼저
 *       조회합니다. 행이 이미 있으면 그 행을 잠그고 UPDATE 하므로, 멀티 노드로 확장돼도 "이미 만들어진
 *       행" 에 대한 동시 수정은 DB 가 직렬화합니다.</li>
 * </ol>
 * 애플리케이션 잠금은 단일 노드 기준 방어입니다. 다중 노드에서 "최초 동시 생성" 까지 완전히 막으려면
 * DB UNIQUE 제약(V3)이 필요합니다. 그 마이그레이션은 팀 확인 후 별도로 넣기로 하고 이번 범위에서는
 * 넣지 않습니다(Issue #129 "미확정 정책", 보고서 J).
 */
@Service
public class WeeklyGoalService {

    static final String GOAL_TYPE = "WEEKLY";
    static final String TARGET_METRIC = "SESSION_COUNT";
    static final String STATUS_ACTIVE = "ACTIVE";

    private final GoalRepository goalRepository;
    private final UserRepository userRepository;
    private final InterviewSessionQueryService sessionQueryService;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    /**
     * 사용자별 직렬화 락. {@code user_id} 당 하나의 자물쇠를 두고 upsert 트랜잭션 전체를 감쌉니다.
     * 다른 사용자끼리는 막지 않습니다. 키가 사용자 수만큼이라 메모리 부담은 작습니다.
     */
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    public WeeklyGoalService(GoalRepository goalRepository,
                             UserRepository userRepository,
                             InterviewSessionQueryService sessionQueryService,
                             Clock clock,
                             PlatformTransactionManager txManager) {
        this.goalRepository = goalRepository;
        this.userRepository = userRepository;
        this.sessionQueryService = sessionQueryService;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 이번 주 목표 조회. 목표를 안 정했으면 {@code null} 을 돌려줍니다(프론트 계약: 미설정은
     * {@code weeklyGoal: null}). 임의 기본값(예: 3회)을 만들지 않습니다.
     *
     * <p>미설정이어도 이번 주 완료한 연습은 있을 수 있지만, 현재 프론트 계약은 {@code weeklyGoal}
     * 객체 안에서만 {@code doneCount} 를 전달하고 미설정이면 객체 자체가 null 입니다. 따라서
     * 미설정 시 {@code doneCount} 를 담을 자리가 없어 {@code null} 로 내려줍니다. 미설정 상태의
     * 실적 노출이 필요하면 프론트 계약 확장이 선행돼야 합니다(보고서 H·J).
     */
    @Transactional(readOnly = true)
    public WeeklyGoalResponse getThisWeek(String userId) {
        WeeklyGoalRange week = WeeklyGoalRange.thisWeek(clock);
        return goalRepository
                .findThisWeek(userId, GOAL_TYPE, TARGET_METRIC, week.startDate())
                .map(goal -> new WeeklyGoalResponse(goal.getTargetValue(), doneCount(userId, week)))
                .orElse(null);
    }

    /**
     * 이번 주 목표 저장·수정(upsert). 없으면 새로 만들고, 있으면 {@code target_value} 만 바꿉니다.
     * 저장 후 실적을 다시 세어 함께 돌려줍니다.
     *
     * @param targetValue 1~7. 컨트롤러의 {@code @Valid} 가 범위를 보장하지만, 서비스 단독 호출·
     *                    회귀를 대비해 여기서도 한 번 더 막습니다(방어적).
     */
    public WeeklyGoalResponse upsertThisWeek(String userId, int targetValue) {
        if (targetValue < 1 || targetValue > 7) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이번 주 목표는 1~7회입니다");
        }
        // 사용자 단위 직렬화: 트랜잭션 커밋까지 블록 안에서 끝나므로 "조회→INSERT→커밋" 경합 창이 없다.
        synchronized (userLocks.computeIfAbsent(userId, key -> new Object())) {
            return txTemplate.execute(status -> upsert(userId, targetValue));
        }
    }

    private WeeklyGoalResponse upsert(String userId, int targetValue) {
        WeeklyGoalRange week = WeeklyGoalRange.thisWeek(clock);

        Optional<Goal> existing = goalRepository
                .findThisWeekForUpdate(userId, GOAL_TYPE, TARGET_METRIC, week.startDate());

        Goal goal = existing
                .map(found -> {
                    found.updateTargetValue(targetValue);
                    return found;
                })
                .orElseGet(() -> goalRepository.save(newGoal(userId, targetValue, week)));

        return new WeeklyGoalResponse(goal.getTargetValue(), doneCount(userId, week));
    }

    private Goal newGoal(String userId, int targetValue, WeeklyGoalRange week) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return Goal.builder()
                .user(user)
                .goalType(GOAL_TYPE)
                .targetMetric(TARGET_METRIC)
                .targetValue(targetValue)
                .startDate(week.startDate())
                .endDate(week.endDate())
                .status(STATUS_ACTIVE)
                .build();
    }

    /** 이번 주(월~일, KST) 완료 연습 수. 홈 통계와 같은 창구를 재사용(COMPLETED 만, 재연습 포함). */
    private long doneCount(String userId, WeeklyGoalRange week) {
        return sessionQueryService.countCompletedPracticeInPeriod(
                userId, week.startInclusive(), week.endExclusive());
    }
}
