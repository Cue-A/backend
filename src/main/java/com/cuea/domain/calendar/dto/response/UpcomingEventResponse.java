package com.cuea.domain.calendar.dto.response;

import com.cuea.domain.calendar.entity.CalendarEvent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 홈 "다가오는 면접일" 한 건. 프론트 홈 계약
 * ({@code frontend} {@code src/domain/home/types/home.ts} 의 {@code UpcomingEvent})과 1:1 입니다:
 * {@code { eventId, title, date, time | null }}.
 *
 * <p>월별 응답({@code CalendarEventResponse})과 달리 홈 카드는 날짜·시각을 이미 쪼갠 형태를
 * 기대합니다. {@code scheduledAt} 을 KST 로 옮겨 {@code date}(YYYY-MM-DD)와 {@code time}(HH:mm)로
 * 나누고, {@code timeSpecified=false} 면 {@code time=null} 입니다.
 *
 * @param eventId 일정 ID(문자열)
 * @param title   일정 제목
 * @param date    KST 날짜({@code YYYY-MM-DD})
 * @param time    KST 시각({@code HH:mm}). 시간 미정이면 null
 */
public record UpcomingEventResponse(
        String eventId,
        String title,
        String date,
        String time
) {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public static UpcomingEventResponse from(CalendarEvent event, ZoneId zone) {
        var kst = event.getScheduledAt().atZoneSameInstant(zone);
        return new UpcomingEventResponse(
                String.valueOf(event.getCalendarEventId()),
                event.getTitle(),
                kst.format(DATE),
                event.isTimeSpecified() ? kst.format(TIME) : null);
    }
}
