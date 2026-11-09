package com.cuea.domain.calendar.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.calendar.dto.request.CalendarEventRequest;
import com.cuea.domain.calendar.dto.response.CalendarEventResponse;
import com.cuea.domain.calendar.entity.CalendarEvent;
import com.cuea.domain.calendar.repository.CalendarEventRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 면접 일정의 등록·단건 조회·수정·삭제(Issue #130).
 *
 * <p>모든 접근은 {@code userId} 를 조회 조건에 묶어 본인 데이터로 제한합니다. 없거나 남의
 * 일정이면 구별 없이 {@link ErrorCode#CALENDAR_EVENT_NOT_FOUND}(404) — 남의 일정 존재 여부를
 * 노출하지 않습니다. 삭제는 멱등하지 않습니다(이미 없으면 404, owner-not-found 와 일관).
 *
 * <p>입력 검증·정규화는 {@link CalendarEventCommand} 한곳에서 합니다.
 */
@Service
@RequiredArgsConstructor
public class CalendarEventService {

    private final CalendarEventRepository calendarEventRepository;
    private final UserRepository userRepository;

    /** 일정 등록. 저장 후 생성된 일정을 돌려줍니다. */
    @Transactional
    public CalendarEventResponse create(String userId, CalendarEventRequest request) {
        CalendarEventCommand command = CalendarEventCommand.from(request);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        CalendarEvent event = CalendarEvent.create(
                user, command.title(), command.scheduledAt(), command.timeSpecified(), command.memo());
        return CalendarEventResponse.from(calendarEventRepository.save(event));
    }

    /** 일정 단건 조회. 본인 것만. */
    @Transactional(readOnly = true)
    public CalendarEventResponse get(String userId, Long calendarEventId) {
        return CalendarEventResponse.from(findOwned(userId, calendarEventId));
    }

    /** 일정 전체 수정(PUT). 등록과 같은 네 칸을 바꿉니다. 본인 것만. */
    @Transactional
    public CalendarEventResponse update(String userId, Long calendarEventId, CalendarEventRequest request) {
        CalendarEventCommand command = CalendarEventCommand.from(request);
        CalendarEvent event = findOwned(userId, calendarEventId);
        event.update(command.title(), command.scheduledAt(), command.timeSpecified(), command.memo());
        return CalendarEventResponse.from(event);
    }

    /** 일정 삭제. 본인 것만. 없거나 남의 것이면 404(멱등하지 않음). */
    @Transactional
    public void delete(String userId, Long calendarEventId) {
        int deleted = calendarEventRepository.deleteByCalendarEventIdAndUser_UserId(calendarEventId, userId);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.CALENDAR_EVENT_NOT_FOUND);
        }
    }

    private CalendarEvent findOwned(String userId, Long calendarEventId) {
        return calendarEventRepository.findByCalendarEventIdAndUser_UserId(calendarEventId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CALENDAR_EVENT_NOT_FOUND));
    }
}
