package com.cuea.domain.calendar.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.calendar.dto.request.CalendarEventRequest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 일정 등록·수정 입력 검증·정규화(프론트 growthApi·growthMock 규칙과 일치). */
class CalendarEventCommandTest {

    private static CalendarEventRequest request(String title, String scheduledAt, Boolean timeSpecified, String memo) {
        return new CalendarEventRequest(title, scheduledAt, timeSpecified, memo);
    }

    @Test
    void 정상_입력은_정규화된_명령을_만든다() {
        CalendarEventCommand command = CalendarEventCommand.from(
                request("  네이버 1차 면접 ", "2026-08-14T14:00:00+09:00", true, " 포트폴리오 대비 "));

        assertThat(command.title()).isEqualTo("네이버 1차 면접");
        assertThat(command.scheduledAt()).isEqualTo(OffsetDateTime.parse("2026-08-14T14:00:00+09:00"));
        assertThat(command.timeSpecified()).isTrue();
        assertThat(command.memo()).isEqualTo("포트폴리오 대비");
    }

    @Test
    void 제목이_공백뿐이면_INVALID_REQUEST() {
        assertInvalid(request("   ", "2026-08-14T14:00:00+09:00", false, null));
    }

    @Test
    void 제목이_null_이면_INVALID_REQUEST() {
        assertInvalid(request(null, "2026-08-14T14:00:00+09:00", false, null));
    }

    @Test
    void 제목이_50자를_넘으면_INVALID_REQUEST() {
        assertInvalid(request("가".repeat(51), "2026-08-14T14:00:00+09:00", false, null));
    }

    @Test
    void 제목_50자_경계는_허용한다() {
        CalendarEventCommand command = CalendarEventCommand.from(
                request("가".repeat(50), "2026-08-14T14:00:00+09:00", false, null));
        assertThat(command.title()).hasSize(50);
    }

    @Test
    void 메모가_비었거나_공백뿐이면_null_로_저장한다() {
        assertThat(CalendarEventCommand.from(request("제목", "2026-08-14T14:00:00+09:00", false, "")).memo()).isNull();
        assertThat(CalendarEventCommand.from(request("제목", "2026-08-14T14:00:00+09:00", false, "   ")).memo()).isNull();
        assertThat(CalendarEventCommand.from(request("제목", "2026-08-14T14:00:00+09:00", false, null)).memo()).isNull();
    }

    @Test
    void 메모가_300자를_넘으면_INVALID_REQUEST() {
        assertInvalid(request("제목", "2026-08-14T14:00:00+09:00", false, "메".repeat(301)));
    }

    @Test
    void 메모_300자_경계는_허용한다() {
        CalendarEventCommand command = CalendarEventCommand.from(
                request("제목", "2026-08-14T14:00:00+09:00", false, "메".repeat(300)));
        assertThat(command.memo()).hasSize(300);
    }

    @Test
    void scheduledAt_파싱_불가면_INVALID_REQUEST() {
        assertInvalid(request("제목", "어제", false, null));
        assertInvalid(request("제목", "", false, null));
        assertInvalid(request("제목", null, false, null));
    }

    @Test
    void timeSpecified_가_null_이면_INVALID_REQUEST() {
        assertInvalid(request("제목", "2026-08-14T14:00:00+09:00", null, null));
    }

    @Test
    void 시간_미정도_정상이다() {
        CalendarEventCommand command = CalendarEventCommand.from(
                request("서류 결과 발표", "2026-08-14T00:00:00+09:00", false, null));
        assertThat(command.timeSpecified()).isFalse();
    }

    private static void assertInvalid(CalendarEventRequest request) {
        assertThatThrownBy(() -> CalendarEventCommand.from(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
