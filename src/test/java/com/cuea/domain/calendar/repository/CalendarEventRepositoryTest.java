package com.cuea.domain.calendar.repository;

import com.cuea.domain.calendar.entity.CalendarEvent;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 면접 일정 저장소를 실제 PostgreSQL 로 봅니다(V3 스키마 반영). 월 경계·정렬·소유자 스코프·삭제를
 * 확인합니다. 월·"지금 이후" 경계는 호출부가 UTC 반열림 구간으로 넘기므로, 여기서는 그 구간을
 * 직접 만들어 전·익월이 섞이지 않는지 봅니다.
 */
class CalendarEventRepositoryTest extends PostgresRepositoryTest {

    @Autowired
    private CalendarEventRepository calendarEventRepository;

    @Autowired
    private TestEntityManager em;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        owner = em.persist(User.create(null, "면접자"));
        other = em.persist(User.create(null, "남"));
    }

    private CalendarEvent persist(User user, String title, String scheduledAt, boolean timeSpecified) {
        return em.persist(CalendarEvent.create(
                user, title, OffsetDateTime.parse(scheduledAt), timeSpecified, null));
    }

    @Test
    void 저장하면_id_와_시각_감사컬럼이_채워진다() {
        CalendarEvent saved = calendarEventRepository.save(
                CalendarEvent.create(owner, "네이버 1차 면접",
                        OffsetDateTime.parse("2026-08-14T14:00:00+09:00"), true, "메모"));
        em.flush();

        assertThat(saved.getCalendarEventId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void 시간_미정_00시_일정도_저장되고_timeSpecified_로_구분된다() {
        CalendarEvent unspecified = persist(owner, "서류 결과 발표", "2026-08-20T00:00:00+09:00", false);
        CalendarEvent midnight = persist(owner, "자정 면접", "2026-08-21T00:00:00+09:00", true);
        em.flush();
        em.clear();

        assertThat(calendarEventRepository.findByCalendarEventIdAndUser_UserId(
                unspecified.getCalendarEventId(), owner.getUserId()))
                .get().extracting(CalendarEvent::isTimeSpecified).isEqualTo(false);
        assertThat(calendarEventRepository.findByCalendarEventIdAndUser_UserId(
                midnight.getCalendarEventId(), owner.getUserId()))
                .get().extracting(CalendarEvent::isTimeSpecified).isEqualTo(true);
    }

    @Test
    void 월_조회는_그_달_KST_구간만_날짜시간_오름차순으로_준다() {
        // 2026-08 KST 구간: [2026-07-31T15:00Z, 2026-08-31T15:00Z)
        OffsetDateTime start = OffsetDateTime.parse("2026-07-31T15:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-31T15:00:00Z");

        // 7월 31일 23:00 KST (= 14:00Z) → 8월 아님(전월 말일, KST 로 7-31)
        persist(owner, "전월", "2026-07-31T23:00:00+09:00", true);
        // 8월 1일 00:00 KST (경계 시작 포함)
        CalendarEvent first = persist(owner, "8월 첫날", "2026-08-01T00:00:00+09:00", false);
        CalendarEvent mid = persist(owner, "8월 중순", "2026-08-14T14:00:00+09:00", true);
        // 8월 31일 23:59 KST 포함
        CalendarEvent last = persist(owner, "8월 말일", "2026-08-31T23:59:00+09:00", true);
        // 9월 1일 00:00 KST (경계 끝 제외)
        persist(owner, "익월", "2026-09-01T00:00:00+09:00", false);
        em.flush();
        em.clear();

        List<CalendarEvent> events = calendarEventRepository.findMonthEvents(owner.getUserId(), start, end);

        assertThat(events).extracting(CalendarEvent::getCalendarEventId)
                .containsExactly(first.getCalendarEventId(), mid.getCalendarEventId(), last.getCalendarEventId());
    }

    @Test
    void 월_조회는_본인_것만_준다() {
        OffsetDateTime start = OffsetDateTime.parse("2026-07-31T15:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-31T15:00:00Z");
        persist(owner, "내 일정", "2026-08-10T10:00:00+09:00", true);
        persist(other, "남 일정", "2026-08-11T10:00:00+09:00", true);
        em.flush();
        em.clear();

        List<CalendarEvent> events = calendarEventRepository.findMonthEvents(owner.getUserId(), start, end);

        assertThat(events).extracting(CalendarEvent::getTitle).containsExactly("내 일정");
    }

    @Test
    void 일정이_없는_달은_빈_목록이다() {
        OffsetDateTime start = OffsetDateTime.parse("2026-07-31T15:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-31T15:00:00Z");
        persist(owner, "9월", "2026-09-05T10:00:00+09:00", true);
        em.flush();
        em.clear();

        assertThat(calendarEventRepository.findMonthEvents(owner.getUserId(), start, end)).isEmpty();
    }

    /**
     * 다가오는 일정 조회. 시간 지정 여부에 따라 포함 기준이 다릅니다. 테스트는 두 경계
     * ({@code now}, {@code todayStart}=오늘 00:00 KST)를 직접 만들어 넘깁니다.
     */
    private List<CalendarEvent> upcoming(OffsetDateTime now, int limit) {
        OffsetDateTime todayStart = now.atZoneSameInstant(KST).toLocalDate()
                .atStartOfDay(KST).toOffsetDateTime();
        return calendarEventRepository.findUpcoming(owner.getUserId(), now, todayStart, PageRequest.of(0, limit));
    }

    @Test
    void 오늘_시간미정_일정은_현재가_오후여도_포함된다() {
        // now = 2026-08-15 14:00 KST (= 05:00Z). 오늘 시간 미정 일정은 00:00 KST 라 이미 "지났지만"
        // 날짜 기준이라 오늘이 끝날 때까지 포함돼야 한다.
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z");
        CalendarEvent todayUnspecified = persist(owner, "오늘 시간미정", "2026-08-15T00:00:00+09:00", false);
        em.flush();
        em.clear();

        assertThat(upcoming(now, 10)).extracting(CalendarEvent::getCalendarEventId)
                .containsExactly(todayUnspecified.getCalendarEventId());
    }

    @Test
    void 오늘_시간지정_일정은_현재보다_과거면_제외된다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z"); // 14:00 KST
        persist(owner, "오늘 오전 면접", "2026-08-15T10:00:00+09:00", true); // 지난 시각
        em.flush();
        em.clear();

        assertThat(upcoming(now, 10)).isEmpty();
    }

    @Test
    void 오늘_시간지정_일정은_현재보다_미래면_포함된다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z"); // 14:00 KST
        CalendarEvent future = persist(owner, "오늘 저녁 면접", "2026-08-15T18:00:00+09:00", true);
        em.flush();
        em.clear();

        assertThat(upcoming(now, 10)).extracting(CalendarEvent::getCalendarEventId)
                .containsExactly(future.getCalendarEventId());
    }

    @Test
    void 내일_시간미정_일정은_포함된다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z");
        CalendarEvent tomorrow = persist(owner, "내일 시간미정", "2026-08-16T00:00:00+09:00", false);
        em.flush();
        em.clear();

        assertThat(upcoming(now, 10)).extracting(CalendarEvent::getCalendarEventId)
                .containsExactly(tomorrow.getCalendarEventId());
    }

    @Test
    void 어제_시간미정_일정은_제외된다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z");
        persist(owner, "어제 시간미정", "2026-08-14T00:00:00+09:00", false);
        em.flush();
        em.clear();

        assertThat(upcoming(now, 10)).isEmpty();
    }

    @Test
    void 오늘_00시_시간지정_일정은_시각에_따라_제외될_수_있다() {
        // 00:00 KST 로 지정된 "자정 면접"(timeSpecified=true). now 가 14:00 KST 면 이미 지났으므로 제외.
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z"); // 14:00 KST
        persist(owner, "오늘 자정 면접", "2026-08-15T00:00:00+09:00", true);
        em.flush();
        em.clear();

        assertThat(upcoming(now, 10)).isEmpty();

        // 같은 자정 면접도 now 가 그 이전(전날 23:00 KST)이면 포함된다 — 시각 기준임을 확인.
        OffsetDateTime before = OffsetDateTime.parse("2026-08-14T14:00:00Z"); // 전날 23:00 KST
        assertThat(upcoming(before, 10)).hasSize(1);
    }

    @Test
    void KST_자정_직전직후_날짜경계를_정확히_가른다() {
        // 시간 미정 일정 두 건: 8-15, 8-16 (각 00:00 KST).
        CalendarEvent d15 = persist(owner, "15일 미정", "2026-08-15T00:00:00+09:00", false);
        CalendarEvent d16 = persist(owner, "16일 미정", "2026-08-16T00:00:00+09:00", false);
        em.flush();
        em.clear();

        // now = 8-15 23:59 KST (= 8-15T14:59Z): 오늘(15일) 기준 → 15·16 모두 포함(오늘·미래).
        OffsetDateTime justBeforeMidnight = OffsetDateTime.parse("2026-08-15T14:59:00Z");
        assertThat(upcoming(justBeforeMidnight, 10)).extracting(CalendarEvent::getCalendarEventId)
                .containsExactly(d15.getCalendarEventId(), d16.getCalendarEventId());

        // now = 8-16 00:00 KST (= 8-15T15:00Z): 오늘이 16일로 넘어감 → 15일은 어제라 제외, 16일만.
        OffsetDateTime justAfterMidnight = OffsetDateTime.parse("2026-08-15T15:00:00Z");
        assertThat(upcoming(justAfterMidnight, 10)).extracting(CalendarEvent::getCalendarEventId)
                .containsExactly(d16.getCalendarEventId());
    }

    @Test
    void 시간미정과_시간지정이_섞이면_시각순으로_정렬하고_limit_을_적용한다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z"); // 14:00 KST
        // 오늘 시간 미정(00:00 KST) → 그날 맨 앞으로 와야 함
        CalendarEvent todayUnspecified = persist(owner, "오늘 미정", "2026-08-15T00:00:00+09:00", false);
        // 오늘 저녁 시간 지정
        CalendarEvent todayEvening = persist(owner, "오늘 저녁", "2026-08-15T18:00:00+09:00", true);
        // 내일 시간 지정
        CalendarEvent tomorrow = persist(owner, "내일 오전", "2026-08-16T09:00:00+09:00", true);
        // 모레 시간 미정
        CalendarEvent dayAfter = persist(owner, "모레 미정", "2026-08-17T00:00:00+09:00", false);
        em.flush();
        em.clear();

        // 전체 정렬: 오늘미정(00:00) → 오늘저녁(18:00) → 내일오전 → 모레미정
        assertThat(upcoming(now, 10)).extracting(CalendarEvent::getCalendarEventId)
                .containsExactly(todayUnspecified.getCalendarEventId(), todayEvening.getCalendarEventId(),
                        tomorrow.getCalendarEventId(), dayAfter.getCalendarEventId());

        // limit 은 필터·정렬 뒤 적용 → 앞 2건.
        assertThat(upcoming(now, 2)).extracting(CalendarEvent::getCalendarEventId)
                .containsExactly(todayUnspecified.getCalendarEventId(), todayEvening.getCalendarEventId());
    }

    @Test
    void 다가오는_일정은_정확히_지금인_일정을_포함한다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z");
        CalendarEvent exactlyNow = persist(owner, "지금", "2026-08-15T14:00:00+09:00", true); // = 05:00Z
        em.flush();
        em.clear();

        assertThat(upcoming(now, 5)).extracting(CalendarEvent::getCalendarEventId)
                .containsExactly(exactlyNow.getCalendarEventId());
    }

    @Test
    void 다가오는_일정은_남의_것을_제외한다() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T05:00:00Z");
        OffsetDateTime todayStart = OffsetDateTime.parse("2026-08-14T15:00:00Z"); // 8-15 00:00 KST
        persist(owner, "내 것", "2026-08-16T10:00:00+09:00", true);
        persist(other, "남 시간지정", "2026-08-16T11:00:00+09:00", true);
        persist(other, "남 시간미정", "2026-08-16T00:00:00+09:00", false);
        em.flush();
        em.clear();

        assertThat(calendarEventRepository.findUpcoming(owner.getUserId(), now, todayStart, PageRequest.of(0, 10)))
                .extracting(CalendarEvent::getTitle).containsExactly("내 것");
    }

    @Test
    void 삭제는_본인_것만_지우고_지운_행수를_준다() {
        CalendarEvent mine = persist(owner, "내 것", "2026-08-16T10:00:00+09:00", true);
        CalendarEvent others = persist(other, "남 것", "2026-08-16T11:00:00+09:00", true);
        em.flush();
        em.clear();

        // 남의 일정을 owner 가 지우려 하면 0
        assertThat(calendarEventRepository.deleteByCalendarEventIdAndUser_UserId(
                others.getCalendarEventId(), owner.getUserId())).isZero();
        // 본인 것은 1
        assertThat(calendarEventRepository.deleteByCalendarEventIdAndUser_UserId(
                mine.getCalendarEventId(), owner.getUserId())).isEqualTo(1);
        // 이미 지운 것은 다시 0(멱등하지 않음 — 서비스가 404)
        assertThat(calendarEventRepository.deleteByCalendarEventIdAndUser_UserId(
                mine.getCalendarEventId(), owner.getUserId())).isZero();
        em.clear();

        assertThat(calendarEventRepository.findByCalendarEventIdAndUser_UserId(
                mine.getCalendarEventId(), owner.getUserId())).isEmpty();
        assertThat(calendarEventRepository.findByCalendarEventIdAndUser_UserId(
                others.getCalendarEventId(), other.getUserId())).isPresent();
    }

    @Test
    void 남의_일정_단건_조회는_비어있다() {
        CalendarEvent others = persist(other, "남 것", "2026-08-16T11:00:00+09:00", true);
        em.flush();
        em.clear();

        assertThat(calendarEventRepository.findByCalendarEventIdAndUser_UserId(
                others.getCalendarEventId(), owner.getUserId())).isEmpty();
    }
}
