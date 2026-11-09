package com.cuea.domain.calendar.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.calendar.dto.request.CalendarEventRequest;
import com.cuea.domain.calendar.dto.response.CalendarEventResponse;
import com.cuea.domain.calendar.dto.response.CalendarMonthResponse;
import com.cuea.domain.calendar.dto.response.UpcomingEventListResponse;
import com.cuea.domain.calendar.service.CalendarEventService;
import com.cuea.domain.calendar.service.CalendarQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 면접 일정(캘린더) API(Issue #130).
 *
 * <ul>
 *   <li>{@code GET /api/calendar?month=YYYY-MM} — 월별 캘린더(등록 일정 + 연습 완료 날짜)</li>
 *   <li>{@code POST /api/calendar/events} — 일정 등록</li>
 *   <li>{@code GET /api/calendar/events/upcoming} — 홈 다가오는 일정</li>
 *   <li>{@code GET /api/calendar/events/{calendarEventId}} — 일정 단건</li>
 *   <li>{@code PUT /api/calendar/events/{calendarEventId}} — 일정 수정</li>
 *   <li>{@code DELETE /api/calendar/events/{calendarEventId}} — 일정 삭제</li>
 * </ul>
 *
 * <p>모든 일정은 로그인 사용자({@link CurrentUser}) 본인 데이터로 제한됩니다. 없거나 남의 일정은
 * 404 CALENDAR_EVENT_NOT_FOUND 로 동일하게 처리합니다.
 */
@Tag(name = "면접 일정")
@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarEventService calendarEventService;
    private final CalendarQueryService calendarQueryService;

    @Operation(
            summary = "월별 캘린더 조회",
            description = """
                    month(YYYY-MM, Asia/Seoul)에 속하는 본인 등록 일정과 그 달에 연습 완료한 날짜를 함께 줍니다.
                    events 는 사용자가 직접 등록한 일정으로 날짜·시간 오름차순입니다. practicedDates 는 완료(COMPLETED)
                    세션에서 자동 계산한 KST 날짜(YYYY-MM-DD) 집합으로, 사용자가 등록·수정·삭제할 수 없습니다.
                    month 형식이 아니면 400 INVALID_REQUEST 입니다.""")
    @GetMapping
    @RateLimit(key = "calendar-month", limit = 60, windowSeconds = 60)
    public Result<CalendarMonthResponse> month(
            @CurrentUser String userId,
            @RequestParam String month) {

        return Result.ok(calendarQueryService.month(userId, month));
    }

    @Operation(
            summary = "일정 등록",
            description = """
                    제목(trim 후 1~50자)·scheduledAt(offset ISO)·timeSpecified·memo(≤300자)를 받습니다. 회사명·직무·
                    면접방식은 받지 않습니다. 시간을 안 적은 일정은 그날 00:00 + timeSpecified=false 로 보냅니다.
                    제목/메모 길이 위반·scheduledAt 파싱 실패는 400 INVALID_REQUEST 입니다. 저장 후 생성된 일정을 줍니다.""")
    @PostMapping("/events")
    @RateLimit(key = "calendar-create", limit = 30, windowSeconds = 60)
    public Result<CalendarEventResponse> create(
            @CurrentUser String userId,
            @RequestBody CalendarEventRequest request) {

        return Result.ok(calendarEventService.create(userId, request));
    }

    @Operation(
            summary = "홈 다가오는 일정 조회",
            description = """
                    지금(Asia/Seoul) 이후의 본인 일정을 가까운 순으로 limit 개 줍니다. limit 은 1~10 이며 비우면 3 입니다.
                    범위를 벗어나면 400 INVALID_REQUEST 입니다. 지난 시각 일정은 제외하고, 날짜만 있는 일정도 그 시각이
                    아직 지나지 않았으면 포함됩니다. 각 항목은 scheduledAt 을 KST 로 쪼갠 date(YYYY-MM-DD)·time(HH:mm,
                    시간 미정이면 null)을 줍니다. (백엔드 홈은 영역별로 쪼개져 있어 GET /api/home 통합 API 는 만들지 않습니다.)""")
    @GetMapping("/events/upcoming")
    @RateLimit(key = "calendar-upcoming", limit = 60, windowSeconds = 60)
    public Result<UpcomingEventListResponse> upcoming(
            @CurrentUser String userId,
            @RequestParam(required = false) Integer limit) {

        return Result.ok(calendarQueryService.upcoming(userId, limit));
    }

    @Operation(
            summary = "일정 단건 조회",
            description = "본인 일정만 조회합니다. 없거나 남의 일정이면 404 CALENDAR_EVENT_NOT_FOUND 입니다.")
    @GetMapping("/events/{calendarEventId}")
    @RateLimit(key = "calendar-get", limit = 60, windowSeconds = 60)
    public Result<CalendarEventResponse> get(
            @CurrentUser String userId,
            @PathVariable Long calendarEventId) {

        return Result.ok(calendarEventService.get(userId, calendarEventId));
    }

    @Operation(
            summary = "일정 수정",
            description = """
                    등록과 같은 네 칸(title·scheduledAt·timeSpecified·memo)을 전체 교체(PUT)합니다. 본인 일정만 수정하며
                    없거나 남의 일정이면 404 CALENDAR_EVENT_NOT_FOUND 입니다. 검증 규칙은 등록과 같습니다.""")
    @PutMapping("/events/{calendarEventId}")
    @RateLimit(key = "calendar-update", limit = 30, windowSeconds = 60)
    public Result<CalendarEventResponse> update(
            @CurrentUser String userId,
            @PathVariable Long calendarEventId,
            @RequestBody CalendarEventRequest request) {

        return Result.ok(calendarEventService.update(userId, calendarEventId, request));
    }

    @Operation(
            summary = "일정 삭제",
            description = """
                    본인 일정만 삭제합니다. 없거나 남의 일정이면 404 CALENDAR_EVENT_NOT_FOUND 입니다(멱등하지 않음 —
                    이미 삭제된 일정 재요청도 404).""")
    @DeleteMapping("/events/{calendarEventId}")
    @RateLimit(key = "calendar-delete", limit = 30, windowSeconds = 60)
    public Result<Void> delete(
            @CurrentUser String userId,
            @PathVariable Long calendarEventId) {

        calendarEventService.delete(userId, calendarEventId);
        return Result.ok();
    }
}
