package com.cuea.domain.calendar.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.calendar.dto.response.CalendarEventResponse;
import com.cuea.domain.calendar.dto.response.CalendarMonthResponse;
import com.cuea.domain.calendar.dto.response.UpcomingEventListResponse;
import com.cuea.domain.calendar.dto.response.UpcomingEventResponse;
import com.cuea.domain.calendar.entity.CalendarEvent;
import com.cuea.domain.calendar.repository.CalendarEventRepository;
import com.cuea.domain.home.service.KoreaTime;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 면접 캘린더의 조회(Issue #130): 월별 캘린더 · 홈 다가오는 일정.
 *
 * <p>월·"지금 이후" 경계는 모두 Asia/Seoul({@link KoreaTime})·{@link Clock} 기준으로 계산합니다
 * (홈 통계와 같은 패턴, 테스트가 {@code Clock.fixed} 로 경계를 고정). {@code practicedDates} 는
 * 사용자 등록 값이 아니라 완료 세션에서 자동 계산하므로, 면접 도메인의
 * {@link InterviewSessionQueryService} 를 월 범위로 재사용합니다(도메인 간 참조는 service 레벨).
 */
@Service
@RequiredArgsConstructor
public class CalendarQueryService {

    /** 홈 "다가오는 면접일" 기본 개수. 프론트 시안의 N=3. */
    public static final int DEFAULT_UPCOMING_LIMIT = 3;

    /** 다가오는 일정 limit 상한. 과한 조회를 막습니다. */
    public static final int MAX_UPCOMING_LIMIT = 10;

    private final CalendarEventRepository calendarEventRepository;
    private final InterviewSessionQueryService sessionQueryService;
    private final Clock clock;

    /**
     * 월별 캘린더. 그 달(KST)의 사용자 등록 일정(날짜·시간 오름차순)과, 그 달에 연습 완료한 KST
     * 날짜 집합을 함께 돌려줍니다.
     */
    @Transactional(readOnly = true)
    public CalendarMonthResponse month(String userId, String rawMonth) {
        CalendarMonth month = CalendarMonth.parse(rawMonth);

        List<CalendarEventResponse> events = calendarEventRepository
                .findMonthEvents(userId, month.startInclusive(), month.endExclusive()).stream()
                .map(CalendarEventResponse::from)
                .toList();

        List<String> practicedDates = sessionQueryService
                .findCompletedPracticeDatesInKstBetween(userId, month.startInclusive(), month.endExclusive());

        return new CalendarMonthResponse(month.month(), events, practicedDates);
    }

    /**
     * 홈 "다가오는 면접일". 시간 지정 여부에 따라 포함 기준이 다릅니다(Issue #130 보완).
     *
     * <ul>
     *   <li>시간 지정 일정({@code timeSpecified=true}): 실제 {@code scheduledAt} 을 현재 시각과 비교해
     *       현재 이후·정확히 일치면 포함, 지난 시각이면 제외.</li>
     *   <li>시간 미정 일정({@code timeSpecified=false}): 실제 시각이 아니라 Asia/Seoul 기준 일정
     *       <b>날짜</b>로 판단. 오늘·미래 날짜면 포함, 어제 이전이면 제외. 오늘 등록한 시간 미정
     *       일정이 오늘 자정을 넘겨도 그날이 끝날 때까지 남습니다(저장 시각이 그날 00:00 KST라,
     *       오늘 00:00 KST 이후면 포함하는 것과 같음).</li>
     * </ul>
     *
     * <p>두 경계를 KST 로 계산해 저장소에 넘깁니다. {@code now}=현재 시각, {@code todayStart}=오늘
     * 00:00 KST. 정렬·limit 은 저장소가 필터·정렬 뒤 적용합니다.
     *
     * @param limit null 이면 {@link #DEFAULT_UPCOMING_LIMIT}. 1~{@link #MAX_UPCOMING_LIMIT} 밖이면 {@code INVALID_REQUEST}
     */
    @Transactional(readOnly = true)
    public UpcomingEventListResponse upcoming(String userId, Integer limit) {
        int size = resolveLimit(limit);
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime todayStart = KoreaTime.today(clock).atStartOfDay(KoreaTime.ZONE).toOffsetDateTime();

        List<UpcomingEventResponse> events = calendarEventRepository
                .findUpcoming(userId, now, todayStart, PageRequest.of(0, size)).stream()
                .map(event -> UpcomingEventResponse.from(event, KoreaTime.ZONE))
                .toList();

        return UpcomingEventListResponse.of(events);
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_UPCOMING_LIMIT;
        }
        if (limit < 1 || limit > MAX_UPCOMING_LIMIT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "limit 은 1~%d 입니다".formatted(MAX_UPCOMING_LIMIT));
        }
        return limit;
    }
}
