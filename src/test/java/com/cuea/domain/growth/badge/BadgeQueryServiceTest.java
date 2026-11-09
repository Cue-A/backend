package com.cuea.domain.growth.badge;

import com.cuea.domain.growth.dto.response.BadgeView;
import com.cuea.domain.growth.entity.Badge;
import com.cuea.domain.growth.repository.BadgeRepository;
import com.cuea.domain.growth.repository.UserBadgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BadgeQueryService#overview} 의 <b>소급 지급(reconcile) 실패 격리</b>를 Spring/DB 없이
 * 검증합니다(Issue #131 리뷰 보완). 소급 지급이 예외를 던져도 성장 관리 조회가 500 으로 떨어지지
 * 않고, <b>이미 지급된 배지</b>만으로 5종 응답을 조립해야 합니다. 실패를 삼켜 숨기지 않고(WARN 로그)
 * 다음 조회에서 재시도합니다.
 *
 * <p>조건 판정·실제 지급의 DB 동작은 {@code BadgeGrantServiceIntegrationTest}(Testcontainers)가
 * 따로 봅니다. 여기서는 조립·실패 격리 로직만 결정적으로 고정합니다.
 */
class BadgeQueryServiceTest {

    private static final String USER_ID = "user_1";

    private BadgeGrantService badgeGrantService;
    private BadgeRepository badgeRepository;
    private UserBadgeRepository userBadgeRepository;
    private BadgeQueryService queryService;

    @BeforeEach
    void setUp() {
        badgeGrantService = mock(BadgeGrantService.class);
        badgeRepository = mock(BadgeRepository.class);
        userBadgeRepository = mock(UserBadgeRepository.class);
        queryService = new BadgeQueryService(badgeGrantService, badgeRepository, userBadgeRepository);

        // 정의 5종(이름은 시드 기준). tone 은 BadgeCode 에서 오므로 여기서 정하지 않는다.
        // 각 Badge mock 의 스터빙을 findAll() 스터빙보다 먼저 끝내, 중첩 스터빙 예외를 피한다.
        List<Badge> defs = List.of(
                badge("FIRST_SESSION", "첫 연습"),
                badge("STREAK_3", "3일 연속"),
                badge("PRESSURE_CLEAR", "압박 극복"),
                badge("RISING", "우상향"),
                badge("TEN_SESSIONS", "10회 달성"));
        when(badgeRepository.findAll()).thenReturn(defs);
    }

    private static Badge badge(String code, String name) {
        Badge b = mock(Badge.class);
        when(b.getBadgeCode()).thenReturn(code);
        when(b.getBadgeName()).thenReturn(name);
        return b;
    }

    @Test
    void reconcile_가_실패해도_조회는_이미_지급된_배지로_5종을_돌려준다() {
        // 소급 지급이 DB 장애 등으로 터지는 상황
        doThrow(new RuntimeException("DB down")).when(badgeGrantService).reconcile(USER_ID);
        // 이미 DB 에 지급돼 있던 배지(FIRST_SESSION)
        OffsetDateTime earned = OffsetDateTime.parse("2026-07-01T10:00:00+09:00");
        List<Object[]> earnedRows = new java.util.ArrayList<>();
        earnedRows.add(new Object[]{"FIRST_SESSION", earned});
        when(userBadgeRepository.findEarnedWithTimes(USER_ID)).thenReturn(earnedRows);

        List<BadgeView> badges = queryService.overview(USER_ID);

        // 예외가 전파되지 않고(500 아님) 5종이 그대로 나온다.
        assertThat(badges).hasSize(5);
        assertThat(badges).extracting(BadgeView::badgeCode).containsExactly(
                "FIRST_SESSION", "STREAK_3", "PRESSURE_CLEAR", "RISING", "TEN_SESSIONS");
        // 이미 지급된 배지만 earnedAt 이 채워지고, 나머지는 null.
        assertThat(find(badges, "FIRST_SESSION").earnedAt()).isEqualTo(earned);
        assertThat(find(badges, "STREAK_3").earnedAt()).isNull();
        // reconcile 은 시도됐다(삼켜 숨기지 않고 호출 후 실패를 로깅).
        verify(badgeGrantService).reconcile(USER_ID);
    }

    @Test
    void reconcile_가_성공하면_5종을_정상_조립한다() {
        when(userBadgeRepository.findEarnedWithTimes(anyString())).thenReturn(List.of());

        List<BadgeView> badges = queryService.overview(USER_ID);

        assertThat(badges).hasSize(5);
        assertThat(badges).allSatisfy(b -> assertThat(b.earnedAt()).isNull());
        verify(badgeGrantService).reconcile(USER_ID);
    }

    private BadgeView find(List<BadgeView> badges, String code) {
        return badges.stream().filter(b -> b.badgeCode().equals(code)).findFirst().orElseThrow();
    }
}
