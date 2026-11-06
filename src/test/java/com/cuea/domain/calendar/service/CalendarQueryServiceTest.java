package com.cuea.domain.calendar.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.calendar.dto.response.CalendarMonthResponse;
import com.cuea.domain.calendar.dto.response.UpcomingEventListResponse;
import com.cuea.domain.calendar.entity.CalendarEvent;
import com.cuea.domain.calendar.repository.CalendarEventRepository;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 월별 조회·다가오는 일정의 경계·위임을 Clock 고정으로 봅니다. 저장소·세션 창구는 mock. */
class CalendarQueryServiceTest {

    private static final String USER = "user-1";

    private CalendarEventRepository calendarEventRepository;
    private InterviewSessionQueryService sessionQueryService;

    @BeforeEach
    void setUp() {
        calendarEventRepository = mock(CalendarEventRepository.class);
        sessionQueryService = mock(InterviewSessionQueryService.class);
    }

    private CalendarQueryService serviceAt(OffsetDateTime now) {
        Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);
        return new CalendarQueryService(calendarEventRepository, sessionQueryService, clock);
    }

    private static CalendarEvent event(long id, String scheduledAt, boolean timeSpecified) {
        return CalendarEvent.builder()
                .calendarEventId(id)
                .user(User.create(null, "면접자"))
                .title("일정 " + id)
                .scheduledAt(OffsetDateTime.parse(scheduledAt))
                .timeSpecified(timeSpecified)
                .build();
    }

    @Test
    void 월별_조회는_KST_경계를_저장소에_넘기고_연습날짜를_재사용한다() {
        CalendarQueryService service = serviceAt(OffsetDateTime.parse("2026-08-15T00:00:00Z"));
        when(calendarEventRepository.findMonthEvents(eq(USER), any(), any()))
                .thenReturn(List.of(event(1L, "2026-08-14T14:00:00+09:00", true)));
        when(sessionQueryService.findCompletedPracticeDatesInKstBetween(eq(USER), any(), any()))
                .thenReturn(List.of("2026-08-03", "2026-08-07"));

        CalendarMonthResponse response = service.month(USER, "2026-08");

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(calendarEventRepository).findMonthEvents(eq(USER), start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(OffsetDateTime.parse("2026-07-31T15:00:00+00:00"));
        assertThat(end.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-31T15:00:00+00:00"));

        assertThat(response.month()).isEqualTo("2026-08");
        assertThat(response.events()).hasSize(1);
        assertThat(response.events().get(0).calendarEventId()).isEqualTo("1");
        assertThat(response.practicedDates()).containsExactly("2026-08-03", "2026-08-07");
    }

    @Test
    void 잘못된_month_는_저장소를_부르지_않고_INVALID_REQUEST() {
        CalendarQueryService service = serviceAt(OffsetDateTime.parse("2026-08-15T00:00:00Z"));
        assertThatThrownBy(() -> service.month(USER, "2026-13"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void 다가오는_일정은_지금시각을_기준으로_limit_개_조회한다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z"); // 14:00 KST
        CalendarQueryService service = serviceAt(now);
        when(calendarEventRepository.findUpcoming(eq(USER), any(), any(), any()))
                .thenReturn(List.of(
                        event(1L, "2026-08-16T10:00:00+09:00", true),
                        event(2L, "2026-08-20T00:00:00+09:00", false)));

        UpcomingEventListResponse response = service.upcoming(USER, null);

        ArgumentCaptor<OffsetDateTime> nowArg = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> todayStart = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(calendarEventRepository).findUpcoming(eq(USER), nowArg.capture(), todayStart.capture(), any());
        // now = 현재 시각, todayStart = 오늘 00:00 KST (= 2026-08-14 15:00Z, now 가 14:00 KST 이므로 같은 날)
        assertThat(nowArg.getValue().toInstant()).isEqualTo(now.toInstant());
        assertThat(todayStart.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-14T15:00:00+00:00"));

        assertThat(response.events()).hasSize(2);
        // 시간 지정 일정은 HH:mm, 시간 미정은 null
        assertThat(response.events().get(0).time()).isEqualTo("10:00");
        assertThat(response.events().get(0).date()).isEqualTo("2026-08-16");
        assertThat(response.events().get(1).time()).isNull();
        assertThat(response.events().get(1).date()).isEqualTo("2026-08-20");
    }

    @Test
    void 기본_limit_은_3_이다() {
        CalendarQueryService service = serviceAt(OffsetDateTime.parse("2026-08-15T05:00:00Z"));
        when(calendarEventRepository.findUpcoming(eq(USER), any(), any(), any())).thenReturn(List.of());

        service.upcoming(USER, null);

        verify(calendarEventRepository).findUpcoming(eq(USER), any(), any(),
                eq(org.springframework.data.domain.PageRequest.of(0, CalendarQueryService.DEFAULT_UPCOMING_LIMIT)));
    }

    @Test
    void limit_범위_밖이면_INVALID_REQUEST() {
        CalendarQueryService service = serviceAt(OffsetDateTime.parse("2026-08-15T05:00:00Z"));
        assertInvalidLimit(service, 0);
        assertInvalidLimit(service, CalendarQueryService.MAX_UPCOMING_LIMIT + 1);
        assertInvalidLimit(service, -1);
    }

    @Test
    void limit_경계_1_과_10_은_허용한다() {
        CalendarQueryService service = serviceAt(OffsetDateTime.parse("2026-08-15T05:00:00Z"));
        when(calendarEventRepository.findUpcoming(eq(USER), any(), any(), any())).thenReturn(List.of());

        service.upcoming(USER, 1);
        service.upcoming(USER, CalendarQueryService.MAX_UPCOMING_LIMIT);

        verify(calendarEventRepository).findUpcoming(eq(USER), any(), any(),
                eq(org.springframework.data.domain.PageRequest.of(0, 1)));
        verify(calendarEventRepository).findUpcoming(eq(USER), any(), any(),
                eq(org.springframework.data.domain.PageRequest.of(0, CalendarQueryService.MAX_UPCOMING_LIMIT)));
    }

    private void assertInvalidLimit(CalendarQueryService service, int limit) {
        assertThatThrownBy(() -> service.upcoming(USER, limit))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
