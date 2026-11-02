package com.cuea.domain.home.service;

import com.cuea.domain.home.dto.response.HomePracticeResponse;
import com.cuea.domain.interview.repository.DailyActivityRow;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.report.repository.MonthlyScoreRow;
import java.math.BigDecimal;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.domain.report.repository.ScorePointRow;
import com.cuea.domain.report.repository.ScoreSummaryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 홈 연습 기록(Issue #114) 조립 로직을 Clock 고정·mock 리포지토리로 봅니다. 기간 창 계산, 잔디 빈 날
 * 0 채움, 평균 반올림/최고, 회차·월 추이 변환을 검증합니다. KST 경계 정확성은 Testcontainers 리포지토리
 * 테스트에서 따로 봅니다.
 */
class HomePracticeQueryServiceTest {

    private static final String USER = "user-1";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private InterviewSessionRepository sessionRepository;
    private ReportRepository reportRepository;
    private HomePracticeQueryService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        reportRepository = mock(ReportRepository.class);
        Instant noonKst = TODAY.atTime(12, 0).atZone(KoreaTime.ZONE).toInstant();
        service = new HomePracticeQueryService(sessionRepository, reportRepository,
                Clock.fixed(noonKst, ZoneOffset.UTC));
        // 기본 스텁: 비어 있음
        when(sessionRepository.countDailyActivityInKst(anyString(), any(), any())).thenReturn(List.of());
        when(sessionRepository.countCompletedInPeriod(anyString(), any(), any())).thenReturn(0L);
        when(reportRepository.summarizeScores(anyString(), any(), any()))
                .thenReturn(new ScoreSummaryRow(null, null));
        when(reportRepository.findScorePointsLatestFirst(anyString(), any(), any(), any())).thenReturn(List.of());
        when(reportRepository.findMonthlyScoreAverages(anyString(), any(), any())).thenReturn(List.of());
    }

    @Test
    void 기간_4w_는_오늘_포함_28일이고_모든_날을_오름차순으로_채운다() {
        HomePracticeResponse response = service.practice(USER, PracticePeriod.FOUR_WEEKS);

        assertThat(response.period()).isEqualTo("4w");
        assertThat(response.endDate()).isEqualTo("2026-11-15");
        assertThat(response.startDate()).isEqualTo("2026-10-19"); // 15 - 27일
        assertThat(response.days()).hasSize(28);
        assertThat(response.days().get(0).date()).isEqualTo("2026-10-19");
        assertThat(response.days().get(27).date()).isEqualTo("2026-11-15");
        // 전부 쉰 날이면 count 0
        assertThat(response.days()).allSatisfy(day -> assertThat(day.count()).isZero());
    }

    @Test
    void 잔디는_DB_에_있는_날만_채우고_나머지는_0_이며_activeDays_와_일치한다() {
        when(sessionRepository.countDailyActivityInKst(anyString(), any(), any())).thenReturn(List.of(
                new DailyActivityRow("2026-11-14", 3),
                new DailyActivityRow("2026-11-15", 1)));
        when(sessionRepository.countCompletedInPeriod(anyString(), any(), any())).thenReturn(4L);

        HomePracticeResponse response = service.practice(USER, PracticePeriod.FOUR_WEEKS);

        assertThat(response.totalCount()).isEqualTo(4);
        assertThat(response.activeDays()).isEqualTo(2);
        assertThat(response.days()).filteredOn(day -> day.count() > 0)
                .extracting(HomePracticeResponse.ActivityDay::date)
                .containsExactly("2026-11-14", "2026-11-15");
        assertThat(response.days()).filteredOn(day -> day.date().equals("2026-11-14"))
                .singleElement().satisfies(day -> assertThat(day.count()).isEqualTo(3));
    }

    @Test
    void 평균은_반올림_정수이고_최고를_그대로_준다() {
        when(reportRepository.summarizeScores(anyString(), any(), any()))
                .thenReturn(new ScoreSummaryRow(new BigDecimal("82.5"), 91)); // 82.5 -> 83

        HomePracticeResponse response = service.practice(USER, PracticePeriod.FOUR_WEEKS);

        assertThat(response.averageScore()).isEqualTo(83);
        assertThat(response.bestScore()).isEqualTo(91);
    }

    @Test
    void 유효_점수가_없으면_평균과_최고는_null_이다() {
        when(reportRepository.summarizeScores(anyString(), any(), any()))
                .thenReturn(new ScoreSummaryRow(null, null));

        HomePracticeResponse response = service.practice(USER, PracticePeriod.FOUR_WEEKS);

        assertThat(response.averageScore()).isNull();
        assertThat(response.bestScore()).isNull();
    }

    @Test
    void sessionSummary_가_null_이어도_안전하게_null_을_준다() {
        when(reportRepository.summarizeScores(anyString(), any(), any())).thenReturn(null);

        HomePracticeResponse response = service.practice(USER, PracticePeriod.FOUR_WEEKS);

        assertThat(response.averageScore()).isNull();
        assertThat(response.bestScore()).isNull();
    }

    @Test
    void 회차_추이는_최근순_입력을_오래된_순으로_뒤집고_최대_8개를_요청한다() {
        // 리포지토리는 최신순으로 준다(최신이 앞). 서비스가 오래된 순으로 뒤집어야 한다.
        when(reportRepository.findScorePointsLatestFirst(anyString(), any(), any(), any())).thenReturn(List.of(
                new ScorePointRow(java.time.Instant.parse("2026-11-15T01:00:00Z"), 91),
                new ScorePointRow(java.time.Instant.parse("2026-11-10T01:00:00Z"), 85),
                new ScorePointRow(java.time.Instant.parse("2026-11-01T01:00:00Z"), 78)));

        HomePracticeResponse response = service.practice(USER, PracticePeriod.TEN_WEEKS);

        assertThat(response.trend().unit()).isEqualTo("session");
        assertThat(response.trend().points()).extracting(HomePracticeResponse.ScorePoint::score)
                .containsExactly(78, 85, 91); // 오래된 순
        // 라벨·date 는 KST 로 만든다(01:00Z = 10:00 KST, 같은 날)
        assertThat(response.trend().points().get(2).date()).isEqualTo("2026-11-15");
        assertThat(response.trend().points().get(2).label()).isEqualTo("11월 15일");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(reportRepository)
                .findScorePointsLatestFirst(anyString(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(8);
    }

    @Test
    void 월_추이는_월평균을_반올림하고_라벨을_만든다() {
        when(reportRepository.findMonthlyScoreAverages(anyString(), any(), any())).thenReturn(List.of(
                new MonthlyScoreRow("2026-09", new BigDecimal("70.4")),
                new MonthlyScoreRow("2026-10", new BigDecimal("80.6"))));

        HomePracticeResponse response = service.practice(USER, PracticePeriod.SIX_MONTHS);

        assertThat(response.trend().unit()).isEqualTo("month");
        assertThat(response.trend().points()).extracting(HomePracticeResponse.ScorePoint::date)
                .containsExactly("2026-09", "2026-10");
        assertThat(response.trend().points()).extracting(HomePracticeResponse.ScorePoint::score)
                .containsExactly(70, 81); // 70.4->70, 80.6->81
        assertThat(response.trend().points().get(0).label()).isEqualTo("2026년 9월");
    }

    @Test
    void 월_추이에_점수가_없으면_빈_points_다() {
        when(reportRepository.findMonthlyScoreAverages(anyString(), any(), any())).thenReturn(List.of());

        HomePracticeResponse response = service.practice(USER, PracticePeriod.ONE_YEAR);

        assertThat(response.trend().unit()).isEqualTo("month");
        assertThat(response.trend().points()).isEmpty();
    }

    @Test
    void 기간별_일수가_맞다() {
        assertThat(service.practice(USER, PracticePeriod.FOUR_WEEKS).days()).hasSize(28);
        assertThat(service.practice(USER, PracticePeriod.TEN_WEEKS).days()).hasSize(70);
        assertThat(service.practice(USER, PracticePeriod.SIX_MONTHS).days()).hasSize(182);
        assertThat(service.practice(USER, PracticePeriod.ONE_YEAR).days()).hasSize(364);
    }

    @Test
    void 기간_창은_KST_자정_반열림_구간으로_계산된다() {
        service.practice(USER, PracticePeriod.FOUR_WEEKS);

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        org.mockito.Mockito.verify(sessionRepository)
                .countDailyActivityInKst(eq(USER), start.capture(), end.capture());

        // startDate=2026-10-19 00:00 KST = 2026-10-18T15:00Z, endExclusive=2026-11-16 00:00 KST = 2026-11-15T15:00Z
        assertThat(start.getValue().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-18T15:00:00Z").toInstant());
        assertThat(end.getValue().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-15T15:00:00Z").toInstant());
    }
}
