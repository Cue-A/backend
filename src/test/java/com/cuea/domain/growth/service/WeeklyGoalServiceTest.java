package com.cuea.domain.growth.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.growth.dto.response.WeeklyGoalResponse;
import com.cuea.domain.growth.entity.Goal;
import com.cuea.domain.growth.repository.GoalRepository;
import com.cuea.domain.home.service.KoreaTime;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주간 목표 조립 로직을 Clock 고정·mock 창구로 봅니다. 주 경계 계산, upsert 의 생성/수정 분기,
 * 미설정 null, 실적 재계산, 검증을 검증합니다. 동시성·중복 방지·KST 경계의 DB 레벨 정확성은
 * Testcontainers 통합 테스트({@code WeeklyGoalServiceIntegrationTest})에서 따로 봅니다.
 *
 * <p>{@link org.springframework.transaction.support.TransactionTemplate} 은 실제 구현을 쓰되
 * {@link PlatformTransactionManager} 를 mock 으로 둡니다. 템플릿은 {@code getTransaction →
 * 콜백 → commit} 순으로 돌아, mock 매니저가 status 를 돌려주기만 하면 콜백(upsert)이 그대로 실행됩니다.
 */
class WeeklyGoalServiceTest {

    private static final String USER = "user-1";
    // 2026-11-06 금요일 12:00 KST → 그 주 월요일 11-02, 일요일 11-08
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 8);

    private GoalRepository goalRepository;
    private UserRepository userRepository;
    private InterviewSessionQueryService sessionQueryService;
    private WeeklyGoalService service;

    @BeforeEach
    void setUp() {
        goalRepository = mock(GoalRepository.class);
        userRepository = mock(UserRepository.class);
        sessionQueryService = mock(InterviewSessionQueryService.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        Instant noonKst = TODAY.atTime(12, 0).atZone(KoreaTime.ZONE).toInstant();
        Clock clock = Clock.fixed(noonKst, ZoneOffset.UTC);

        service = new WeeklyGoalService(goalRepository, userRepository, sessionQueryService, clock, txManager);

        when(sessionQueryService.countCompletedPracticeInPeriod(any(), any(), any())).thenReturn(0L);
    }

    @Test
    void 미설정이면_null_을_돌려주고_임의_기본값을_만들지_않는다() {
        when(goalRepository.findThisWeek(eq(USER), any(), any(), eq(MONDAY))).thenReturn(Optional.empty());

        assertThat(service.getThisWeek(USER)).isNull();
    }

    @Test
    void 설정돼_있으면_목표와_실적을_함께_돌려준다() {
        when(goalRepository.findThisWeek(eq(USER), any(), any(), eq(MONDAY)))
                .thenReturn(Optional.of(goalWith(3)));
        when(sessionQueryService.countCompletedPracticeInPeriod(any(), any(), any())).thenReturn(2L);

        WeeklyGoalResponse response = service.getThisWeek(USER);

        assertThat(response).isNotNull();
        assertThat(response.targetValue()).isEqualTo(3);
        assertThat(response.doneCount()).isEqualTo(2);
    }

    @Test
    void 최초_설정이면_이번_주_월요일_일요일로_새_목표를_저장한다() {
        when(goalRepository.findThisWeekForUpdate(eq(USER), any(), any(), eq(MONDAY)))
                .thenReturn(Optional.empty());
        when(userRepository.findById(USER)).thenReturn(Optional.of(mock(User.class)));
        when(goalRepository.save(any(Goal.class))).thenAnswer(inv -> inv.getArgument(0));

        WeeklyGoalResponse response = service.upsertThisWeek(USER, 5);

        ArgumentCaptor<Goal> saved = ArgumentCaptor.forClass(Goal.class);
        verify(goalRepository).save(saved.capture());
        Goal goal = saved.getValue();
        assertThat(goal.getGoalType()).isEqualTo("WEEKLY");
        assertThat(goal.getTargetMetric()).isEqualTo("SESSION_COUNT");
        assertThat(goal.getTargetValue()).isEqualTo(5);
        assertThat(goal.getStartDate()).isEqualTo(MONDAY);
        assertThat(goal.getEndDate()).isEqualTo(SUNDAY);
        assertThat(goal.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.targetValue()).isEqualTo(5);
    }

    @Test
    void 이미_있으면_새로_저장하지_않고_목표치만_바꾼다() {
        Goal existing = goalWith(2);
        when(goalRepository.findThisWeekForUpdate(eq(USER), any(), any(), eq(MONDAY)))
                .thenReturn(Optional.of(existing));

        WeeklyGoalResponse response = service.upsertThisWeek(USER, 6);

        verify(goalRepository, never()).save(any());
        verify(userRepository, never()).findById(any());
        assertThat(existing.getTargetValue()).isEqualTo(6); // 더티 체킹으로 UPDATE
        assertThat(response.targetValue()).isEqualTo(6);
    }

    @Test
    void 저장_후_실적을_다시_세어_돌려준다() {
        when(goalRepository.findThisWeekForUpdate(eq(USER), any(), any(), eq(MONDAY)))
                .thenReturn(Optional.of(goalWith(3)));
        when(sessionQueryService.countCompletedPracticeInPeriod(any(), any(), any())).thenReturn(4L);

        WeeklyGoalResponse response = service.upsertThisWeek(USER, 3);

        assertThat(response.doneCount()).isEqualTo(4);
    }

    @Test
    void 실적_집계는_월요일_자정KST_부터_다음주_월요일_자정KST_반열림_구간으로_부른다() {
        when(goalRepository.findThisWeekForUpdate(eq(USER), any(), any(), eq(MONDAY)))
                .thenReturn(Optional.of(goalWith(3)));

        service.upsertThisWeek(USER, 3);

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sessionQueryService).countCompletedPracticeInPeriod(eq(USER), start.capture(), end.capture());

        assertThat(start.getValue().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-01T15:00:00Z").toInstant());
        assertThat(end.getValue().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-08T15:00:00Z").toInstant());
    }

    @Test
    void 존재하지_않는_사용자의_최초_설정은_USER_NOT_FOUND_이다() {
        when(goalRepository.findThisWeekForUpdate(eq(USER), any(), any(), eq(MONDAY)))
                .thenReturn(Optional.empty());
        when(userRepository.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertThisWeek(USER, 3))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 범위_밖_목표는_INVALID_REQUEST_이고_DB_를_건드리지_않는다() {
        assertThatThrownBy(() -> service.upsertThisWeek(USER, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> service.upsertThisWeek(USER, 8))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(goalRepository, never()).findThisWeekForUpdate(any(), any(), any(), any());
        verify(goalRepository, never()).save(any());
    }

    @Test
    void 경계값_1회_7회는_그대로_저장한다() {
        when(goalRepository.findThisWeekForUpdate(eq(USER), any(), any(), eq(MONDAY)))
                .thenReturn(Optional.empty());
        when(userRepository.findById(USER)).thenReturn(Optional.of(mock(User.class)));
        when(goalRepository.save(any(Goal.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.upsertThisWeek(USER, 1).targetValue()).isEqualTo(1);
        assertThat(service.upsertThisWeek(USER, 7).targetValue()).isEqualTo(7);
    }

    private Goal goalWith(int targetValue) {
        return Goal.builder()
                .user(mock(User.class))
                .goalType("WEEKLY")
                .targetMetric("SESSION_COUNT")
                .targetValue(targetValue)
                .startDate(MONDAY)
                .endDate(SUNDAY)
                .status("ACTIVE")
                .build();
    }
}
