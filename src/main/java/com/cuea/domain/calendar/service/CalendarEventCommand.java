package com.cuea.domain.calendar.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.calendar.dto.request.CalendarEventRequest;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 일정 등록·수정 입력의 검증·정규화를 한곳에 모읍니다. 등록(POST)과 수정(PUT)이 같은 규칙을
 * 쓰므로 양쪽이 이 팩토리를 공유해 정책이 흩어지지 않게 합니다.
 *
 * <p>규칙(프론트 {@code growthApi.ts} · {@code growthMock.ts} 와 일치):
 * <ul>
 *   <li>{@code title}: 앞뒤 공백을 자른 뒤 1~50자. 범위 밖이면 {@link ErrorCode#INVALID_REQUEST}.</li>
 *   <li>{@code memo}: 최대 300자. 비거나 공백뿐이면 null 로 저장. 초과면 {@code INVALID_REQUEST}.</li>
 *   <li>{@code scheduledAt}: offset 포함 ISO-8601. 파싱 불가면 {@code INVALID_REQUEST}.</li>
 *   <li>{@code timeSpecified}: null 이면 {@code INVALID_REQUEST}(프론트는 항상 boolean 을 보냄).</li>
 * </ul>
 *
 * <p>과거 날짜는 막지 않습니다(명세 없음 — 지난 일정도 등록·수정 허용, 월별 조회에 포함).
 *
 * @param title         정규화된 제목(trim 완료)
 * @param scheduledAt   파싱된 시각
 * @param timeSpecified 시간 지정 여부
 * @param memo          정규화된 메모(비었으면 null)
 */
public record CalendarEventCommand(
        String title,
        OffsetDateTime scheduledAt,
        boolean timeSpecified,
        String memo
) {

    /** 프론트 {@code MAX_EVENT_TITLE_LENGTH}. */
    public static final int MAX_TITLE_LENGTH = 50;

    /** 프론트 {@code MAX_EVENT_MEMO_LENGTH}. */
    public static final int MAX_MEMO_LENGTH = 300;

    public static CalendarEventCommand from(CalendarEventRequest request) {
        String title = request.title() == null ? "" : request.title().strip();
        if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "일정 제목은 1~%d자입니다".formatted(MAX_TITLE_LENGTH));
        }

        String memo = request.memo() == null ? null : request.memo().strip();
        if (memo != null && memo.isEmpty()) {
            memo = null;
        }
        if (memo != null && memo.length() > MAX_MEMO_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "메모는 %d자까지입니다".formatted(MAX_MEMO_LENGTH));
        }

        if (request.timeSpecified() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "timeSpecified 는 필수입니다");
        }

        OffsetDateTime scheduledAt = parseScheduledAt(request.scheduledAt());

        return new CalendarEventCommand(title, scheduledAt, request.timeSpecified(), memo);
    }

    private static OffsetDateTime parseScheduledAt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "날짜는 필수입니다");
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "날짜를 읽을 수 없습니다");
        }
    }
}
