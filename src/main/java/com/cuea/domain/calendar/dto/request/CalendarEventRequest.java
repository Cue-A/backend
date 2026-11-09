package com.cuea.domain.calendar.dto.request;

/**
 * 일정 등록(POST) · 수정(PUT) 공용 요청 본문. 프론트 계약
 * ({@code frontend} {@code src/domain/growth/api/growthResponse.ts} 의
 * {@code CalendarEventCreateRequest})과 1:1 입니다.
 *
 * <p>검증 애너테이션을 달지 않습니다. {@code title} 공백 trim 후 1~50자, {@code memo} 300자,
 * {@code scheduledAt} 파싱 규칙이 서로 얽혀 있어 서비스 한 곳({@code CalendarEventCommand})에서
 * {@code INVALID_REQUEST} 로 처리합니다. Bean Validation 과 수동 검증이 나뉘면 메시지·정책이
 * 두 곳으로 흩어집니다.
 *
 * @param title         일정 제목. 앞뒤 공백을 자른 뒤 1~50자
 * @param scheduledAt   offset 이 붙은 ISO-8601 시각. 시간 미정이면 그날 00:00 이고 {@code timeSpecified=false}
 * @param timeSpecified 시간을 지정했는지. "시간 미정"(00:00,false)과 "자정 면접"(00:00,true)을 구분
 * @param memo          선택 메모. 최대 300자. 비거나 공백뿐이면 null 로 저장
 */
public record CalendarEventRequest(
        String title,
        String scheduledAt,
        Boolean timeSpecified,
        String memo
) {
}
