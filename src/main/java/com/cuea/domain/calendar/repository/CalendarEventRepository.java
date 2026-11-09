package com.cuea.domain.calendar.repository;

import com.cuea.domain.calendar.entity.CalendarEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 면접 일정(사용자 등록). 소유자가 있는 엔티티라 {@code findById} 를 두지 않고 모든 조회·삭제에
 * {@code userId} 를 묶습니다. docs/01-conventions.md 의 "소유자 있는 엔티티는 Repository 를
 * 상속합니다" 참고.
 *
 * <p>월별·다가오는 일정 조회는 KST 월·"지금 이후" 경계를 호출부가 UTC {@link OffsetDateTime}
 * 반열림 구간으로 계산해 넘깁니다. 쿼리는 {@code scheduled_at} 을 그대로 비교해
 * {@code idx_calendar_event_user_scheduled} 를 탑니다(홈 통계와 같은 패턴).
 */
public interface CalendarEventRepository extends Repository<CalendarEvent, Long> {

    CalendarEvent save(CalendarEvent event);

    /** 본인 일정 단건. 없거나 남의 것이면 비어 있습니다(서비스가 404 로 바꿉니다). */
    Optional<CalendarEvent> findByCalendarEventIdAndUser_UserId(Long calendarEventId, String userId);

    /**
     * 그 달(KST)에 속하는 본인 일정. 날짜·시간 오름차순, 같은 시각이면 id 순으로 안정 정렬합니다.
     * 반열림 구간 {@code [monthStart, nextMonthStart)}.
     */
    @Query("""
            select e from CalendarEvent e
             where e.user.userId = :userId
               and e.scheduledAt >= :startInclusive
               and e.scheduledAt < :endExclusive
             order by e.scheduledAt asc, e.calendarEventId asc
            """)
    List<CalendarEvent> findMonthEvents(@Param("userId") String userId,
                                        @Param("startInclusive") OffsetDateTime startInclusive,
                                        @Param("endExclusive") OffsetDateTime endExclusive);

    /**
     * 홈 "다가오는 면접일". 시간 지정 여부에 따라 포함 기준이 다릅니다.
     *
     * <ul>
     *   <li>{@code time_specified = true}: 실제 시각 기준. {@code scheduled_at >= now}(현재 시각 포함)
     *       이면 포함, 이미 지난 시각이면 제외.</li>
     *   <li>{@code time_specified = false}: 날짜 기준. 시간 미정 일정은 그날 00:00(KST)로 저장되므로
     *       {@code scheduled_at >= todayStartInclusive}(오늘 00:00 KST)이면 포함. 즉 오늘·미래 날짜는
     *       포함되고 어제 이전만 제외돼, 오늘 등록한 시간 미정 일정이 오늘 자정 이후에도
     *       <b>그날이 끝날 때까지</b> 남습니다.</li>
     * </ul>
     *
     * <p>경계는 호출부가 KST 로 계산해 UTC {@link OffsetDateTime} 로 넘깁니다({@code now} = 현재 시각,
     * {@code todayStartInclusive} = 오늘 00:00 KST). 정렬은 {@code scheduled_at} 오름차순, 같은 시각이면
     * {@code calendar_event_id} 순으로 안정적입니다. 같은 날에 시간 미정(그날 00:00)과 시간 지정
     * 일정이 섞이면 시간 미정이 자연히 그날 맨 앞에 옵니다. 개수 제한({@code limit})은 필터·정렬
     * 뒤에 {@code pageable} 로 적용됩니다.
     */
    @Query("""
            select e from CalendarEvent e
             where e.user.userId = :userId
               and (
                     (e.timeSpecified = true  and e.scheduledAt >= :now)
                  or (e.timeSpecified = false and e.scheduledAt >= :todayStartInclusive)
               )
             order by e.scheduledAt asc, e.calendarEventId asc
            """)
    List<CalendarEvent> findUpcoming(@Param("userId") String userId,
                                     @Param("now") OffsetDateTime now,
                                     @Param("todayStartInclusive") OffsetDateTime todayStartInclusive,
                                     Pageable pageable);

    /**
     * 본인 일정 삭제. @return 지운 행 수. 없거나 남의 것이면 0(서비스가 404 로 바꿔 멱등하지 않게 404).
     */
    @Modifying
    @Query("delete from CalendarEvent e where e.calendarEventId = :calendarEventId and e.user.userId = :userId")
    int deleteByCalendarEventIdAndUser_UserId(@Param("calendarEventId") Long calendarEventId,
                                              @Param("userId") String userId);
}
