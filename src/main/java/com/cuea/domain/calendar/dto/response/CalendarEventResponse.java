package com.cuea.domain.calendar.dto.response;

import com.cuea.domain.calendar.entity.CalendarEvent;

import java.time.OffsetDateTime;

/**
 * 일정 단건 응답. 프론트 계약({@code CalendarEventResponse})과 필드명·타입이 1:1 입니다.
 *
 * <p><b>{@code calendarEventId} 는 문자열입니다.</b> DB PK 는 {@code Long} 이지만 프론트 타입이
 * {@code string}(React key·식별자로 씀)이라, Jackson 이 숫자로 직렬화하지 않도록 여기서
 * {@code Long.toString()} 으로 변환합니다.
 *
 * @param calendarEventId 일정 ID(문자열)
 * @param title           일정 제목
 * @param scheduledAt     저장된 시각(offset 포함 ISO). {@code timestamptz} 라 UTC 로 정규화되어 있습니다
 * @param timeSpecified   시간 지정 여부
 * @param memo            메모(없으면 null)
 */
public record CalendarEventResponse(
        String calendarEventId,
        String title,
        OffsetDateTime scheduledAt,
        boolean timeSpecified,
        String memo
) {

    public static CalendarEventResponse from(CalendarEvent event) {
        return new CalendarEventResponse(
                String.valueOf(event.getCalendarEventId()),
                event.getTitle(),
                event.getScheduledAt(),
                event.isTimeSpecified(),
                event.getMemo());
    }
}
