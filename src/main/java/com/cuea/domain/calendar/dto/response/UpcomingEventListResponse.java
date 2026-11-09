package com.cuea.domain.calendar.dto.response;

import java.util.List;

/**
 * 홈 "다가오는 면접일" 목록 응답({@code GET /api/calendar/events/upcoming}).
 *
 * <p>프론트 홈 목업은 아직 통합 {@code GET /api/home} 의 {@code upcomingEvents[]} 로 받지만,
 * 백엔드 홈은 영역별로 쪼개져 있어({@code /recent-reports} · {@code /stats} · {@code /practice})
 * 통합 {@code GET /api/home} 을 새로 만들지 않습니다. 일정은 calendar 도메인의 이 엔드포인트가
 * 제공합니다. 프론트는 추후 이 경로로 연결합니다.
 *
 * @param events 지금(KST) 이후의 일정, 가까운 순. {@code limit} 만큼
 */
public record UpcomingEventListResponse(
        List<UpcomingEventResponse> events
) {

    public static UpcomingEventListResponse of(List<UpcomingEventResponse> events) {
        return new UpcomingEventListResponse(events);
    }
}
