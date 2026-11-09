package com.cuea.domain.calendar.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.calendar.dto.request.CalendarEventRequest;
import com.cuea.domain.calendar.dto.response.CalendarEventResponse;
import com.cuea.domain.calendar.entity.CalendarEvent;
import com.cuea.domain.calendar.repository.CalendarEventRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 일정 CRUD 와 소유권·404 처리. 저장소·사용자 조회는 mock 으로 둡니다. */
class CalendarEventServiceTest {

    private static final String USER = "user-1";

    private CalendarEventRepository calendarEventRepository;
    private UserRepository userRepository;
    private CalendarEventService service;

    @BeforeEach
    void setUp() {
        calendarEventRepository = mock(CalendarEventRepository.class);
        userRepository = mock(UserRepository.class);
        service = new CalendarEventService(calendarEventRepository, userRepository);
    }

    private static CalendarEventRequest request(String title, String scheduledAt, Boolean timeSpecified, String memo) {
        return new CalendarEventRequest(title, scheduledAt, timeSpecified, memo);
    }

    private static CalendarEvent eventWithId(long id) {
        return CalendarEvent.builder()
                .calendarEventId(id)
                .user(User.create(null, "면접자"))
                .title("네이버 1차 면접")
                .scheduledAt(OffsetDateTime.parse("2026-08-14T14:00:00+09:00"))
                .timeSpecified(true)
                .memo("메모")
                .build();
    }

    @Test
    void 등록하면_저장하고_생성된_일정을_돌려준다() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(User.create(null, "면접자")));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventResponse response = service.create(USER,
                request(" 네이버 1차 면접 ", "2026-08-14T14:00:00+09:00", true, "포폴 대비"));

        verify(calendarEventRepository).save(any(CalendarEvent.class));
        assertThat(response.title()).isEqualTo("네이버 1차 면접");
        assertThat(response.timeSpecified()).isTrue();
        assertThat(response.memo()).isEqualTo("포폴 대비");
    }

    @Test
    void 등록_시_사용자가_없으면_USER_NOT_FOUND() {
        when(userRepository.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER,
                request("제목", "2026-08-14T14:00:00+09:00", true, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
        verify(calendarEventRepository, never()).save(any());
    }

    @Test
    void 검증_실패면_사용자_조회도_저장도_하지_않는다() {
        assertThatThrownBy(() -> service.create(USER,
                request("   ", "2026-08-14T14:00:00+09:00", true, null)))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).findById(any());
        verify(calendarEventRepository, never()).save(any());
    }

    @Test
    void 단건_조회는_본인_것만_주고_없으면_404() {
        when(calendarEventRepository.findByCalendarEventIdAndUser_UserId(1L, USER))
                .thenReturn(Optional.of(eventWithId(1L)));
        assertThat(service.get(USER, 1L).calendarEventId()).isEqualTo("1");

        when(calendarEventRepository.findByCalendarEventIdAndUser_UserId(2L, USER))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(USER, 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CALENDAR_EVENT_NOT_FOUND));
    }

    @Test
    void 수정은_본인_일정의_네_칸을_바꾼다() {
        CalendarEvent event = eventWithId(1L);
        when(calendarEventRepository.findByCalendarEventIdAndUser_UserId(1L, USER))
                .thenReturn(Optional.of(event));

        CalendarEventResponse response = service.update(USER, 1L,
                request("카카오 2차 면접", "2026-09-01T00:00:00+09:00", false, ""));

        assertThat(event.getTitle()).isEqualTo("카카오 2차 면접");
        assertThat(event.getScheduledAt()).isEqualTo(OffsetDateTime.parse("2026-09-01T00:00:00+09:00"));
        assertThat(event.isTimeSpecified()).isFalse();
        assertThat(event.getMemo()).isNull();
        assertThat(response.title()).isEqualTo("카카오 2차 면접");
    }

    @Test
    void 수정_대상이_없으면_404() {
        when(calendarEventRepository.findByCalendarEventIdAndUser_UserId(9L, USER))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(USER, 9L,
                request("제목", "2026-09-01T00:00:00+09:00", false, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CALENDAR_EVENT_NOT_FOUND));
    }

    @Test
    void 삭제는_지운_행이_있으면_성공한다() {
        when(calendarEventRepository.deleteByCalendarEventIdAndUser_UserId(1L, USER)).thenReturn(1);
        service.delete(USER, 1L);
        verify(calendarEventRepository).deleteByCalendarEventIdAndUser_UserId(1L, USER);
    }

    @Test
    void 이미_없거나_남의_일정_삭제는_404_로_멱등하지_않다() {
        when(calendarEventRepository.deleteByCalendarEventIdAndUser_UserId(1L, USER)).thenReturn(0);
        assertThatThrownBy(() -> service.delete(USER, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CALENDAR_EVENT_NOT_FOUND));
    }
}
