package com.cuea.domain.calendar.dto.response;

import java.util.List;

/**
 * 월별 캘린더 응답({@code GET /api/calendar?month=YYYY-MM}). 프론트 계약
 * ({@code CalendarMonthResponse})과 1:1 입니다.
 *
 * <p>{@code events} 와 {@code practicedDates} 는 성격이 다릅니다. {@code events} 는 사용자가 직접
 * 등록한 일정(calendar_event)이고, {@code practicedDates} 는 완료(COMPLETED) 세션에서 자동
 * 계산한 연습한 날입니다. 사용자가 등록·수정·삭제할 수 없고, 캘린더 범례도 "면접 일정" /
 * "연습 완료" 로 나뉩니다.
 *
 * @param month          조회한 달({@code YYYY-MM})
 * @param events         그 달(KST)에 속한 사용자 등록 일정. 날짜·시간 오름차순
 * @param practicedDates 그 달에 연습 완료한 KST 날짜({@code YYYY-MM-DD}) 집합, 오름차순
 */
public record CalendarMonthResponse(
        String month,
        List<CalendarEventResponse> events,
        List<String> practicedDates
) {
}
