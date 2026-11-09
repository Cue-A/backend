package com.cuea.domain.calendar.controller;

import com.cuea.common.result.Result;
import com.cuea.domain.calendar.dto.request.CalendarEventRequest;
import com.cuea.domain.calendar.dto.response.CalendarEventResponse;
import com.cuea.domain.calendar.dto.response.CalendarMonthResponse;
import com.cuea.domain.calendar.dto.response.UpcomingEventListResponse;
import com.cuea.domain.calendar.dto.response.UpcomingEventResponse;
import com.cuea.domain.calendar.service.CalendarEventService;
import com.cuea.domain.calendar.service.CalendarQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨트롤러가 서비스에 위임하고 {@code Result<T>} 로 감싸는지 Spring 컨텍스트 없이 봅니다.
 * 로그인 가드({@code @CurrentUser})는 {@code EndpointAuthGuardTest} 가 따로 봅니다.
 */
class CalendarControllerTest {

    private static final String USER = "user-1";

    private CalendarEventService calendarEventService;
    private CalendarQueryService calendarQueryService;
    private CalendarController controller;

    @BeforeEach
    void setUp() {
        calendarEventService = mock(CalendarEventService.class);
        calendarQueryService = mock(CalendarQueryService.class);
        controller = new CalendarController(calendarEventService, calendarQueryService);
    }

    private static CalendarEventResponse sampleEvent() {
        return new CalendarEventResponse("1", "네이버 1차 면접",
                OffsetDateTime.parse("2026-08-14T14:00:00+09:00"), true, "메모");
    }

    @Test
    void 월별_조회를_서비스에_위임하고_Result_로_감싼다() {
        CalendarMonthResponse body = new CalendarMonthResponse("2026-08", List.of(sampleEvent()), List.of("2026-08-03"));
        when(calendarQueryService.month(USER, "2026-08")).thenReturn(body);

        Result<CalendarMonthResponse> result = controller.month(USER, "2026-08");

        verify(calendarQueryService).month(USER, "2026-08");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isSameAs(body);
    }

    @Test
    void 등록을_서비스에_위임한다() {
        CalendarEventRequest request = new CalendarEventRequest("제목", "2026-08-14T14:00:00+09:00", true, null);
        CalendarEventResponse body = sampleEvent();
        when(calendarEventService.create(USER, request)).thenReturn(body);

        Result<CalendarEventResponse> result = controller.create(USER, request);

        verify(calendarEventService).create(USER, request);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isSameAs(body);
    }

    @Test
    void 다가오는_일정을_limit_과_함께_위임한다() {
        UpcomingEventListResponse body = UpcomingEventListResponse.of(List.of(
                new UpcomingEventResponse("1", "네이버 1차 면접", "2026-08-16", "10:00")));
        when(calendarQueryService.upcoming(USER, 5)).thenReturn(body);

        Result<UpcomingEventListResponse> result = controller.upcoming(USER, 5);

        verify(calendarQueryService).upcoming(USER, 5);
        assertThat(result.data()).isSameAs(body);
    }

    @Test
    void 다가오는_일정_limit_없으면_null_그대로_넘긴다() {
        UpcomingEventListResponse body = UpcomingEventListResponse.of(List.of());
        when(calendarQueryService.upcoming(USER, null)).thenReturn(body);

        controller.upcoming(USER, null);

        verify(calendarQueryService).upcoming(USER, null);
    }

    @Test
    void 단건_조회를_위임한다() {
        CalendarEventResponse body = sampleEvent();
        when(calendarEventService.get(USER, 1L)).thenReturn(body);

        Result<CalendarEventResponse> result = controller.get(USER, 1L);

        verify(calendarEventService).get(USER, 1L);
        assertThat(result.data()).isSameAs(body);
    }

    @Test
    void 수정을_위임한다() {
        CalendarEventRequest request = new CalendarEventRequest("바뀐 제목", "2026-09-01T00:00:00+09:00", false, null);
        CalendarEventResponse body = sampleEvent();
        when(calendarEventService.update(USER, 1L, request)).thenReturn(body);

        Result<CalendarEventResponse> result = controller.update(USER, 1L, request);

        verify(calendarEventService).update(USER, 1L, request);
        assertThat(result.data()).isSameAs(body);
    }

    @Test
    void 삭제를_위임하고_빈_Result_를_준다() {
        Result<Void> result = controller.delete(USER, 1L);

        verify(calendarEventService).delete(USER, 1L);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isNull();
    }
}
