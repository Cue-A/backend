package com.cuea.domain.record.service;

import com.cuea.common.paging.PageRequests;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.record.dto.request.PracticeRecordStatus;
import com.cuea.domain.record.dto.response.PracticeRecordListResponse;
import com.cuea.domain.record.dto.response.PracticeRecordResponse;
import com.cuea.domain.report.dto.response.ReportSummary;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.service.ReportSummaryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeRecordQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-08-02T10:00:00Z");

    private InterviewSessionQueryService sessionQueryService;
    private ReportSummaryQueryService reportSummaryQueryService;
    private PracticeRecordQueryService service;

    @BeforeEach
    void setUp() {
        sessionQueryService = mock(InterviewSessionQueryService.class);
        reportSummaryQueryService = mock(ReportSummaryQueryService.class);
        service = new PracticeRecordQueryService(sessionQueryService, reportSummaryQueryService);
    }

    @Test
    void 세션에_회차와_리포트_요약을_붙인다() {
        InterviewSession withReport = session("sess_1", Company.builder().companyName("삼성전자").build(),
                SessionStatus.COMPLETED);
        InterviewSession running = session("sess_2", null, SessionStatus.IN_PROGRESS);
        givenSessions(new PageImpl<>(List.of(withReport, running), PageRequest.of(0, 2), 12));
        when(sessionQueryService.roundsOf(USER_ID, List.of(withReport, running)))
                .thenReturn(Map.of("sess_1", 1, "sess_2", 2));
        ReportSummary summary = new ReportSummary(UUID.randomUUID().toString(), ReportStatus.COMPLETED, 84);
        when(reportSummaryQueryService.findBySessionIds(USER_ID, List.of("sess_1", "sess_2")))
                .thenReturn(Map.of("sess_1", summary));

        PracticeRecordListResponse response = service.list(USER_ID, null, null, null, null, null);

        assertThat(response.totalElements()).isEqualTo(12);
        PracticeRecordResponse first = response.records().get(0);
        assertThat(first.companyName()).isEqualTo("삼성전자");
        assertThat(first.jobRole()).isEqualTo("마케팅");
        assertThat(first.persona()).isEqualTo(Persona.PRESSURE);
        assertThat(first.questionCount()).isEqualTo(9);
        assertThat(first.round()).isEqualTo(1);
        assertThat(first.createdAt()).isEqualTo(CREATED_AT);
        assertThat(first.report()).isEqualTo(summary);
        PracticeRecordResponse second = response.records().get(1);
        assertThat(second.companyName()).isNull();
        assertThat(second.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(second.round()).isEqualTo(2);
        assertThat(second.report()).isNull();
    }

    @Test
    void 상태를_안_주면_중단을_뺀_두_상태를_본다() {
        givenSessions(Page.empty());

        service.list(USER_ID, null, null, null, null, null);

        assertThat(captureStatuses()).containsExactly(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS);
    }

    @Test
    void 상태를_주면_그_상태만_본다() {
        givenSessions(Page.empty());

        service.list(USER_ID, PracticeRecordStatus.IN_PROGRESS, null, null, null, null);

        assertThat(captureStatuses()).containsExactly(SessionStatus.IN_PROGRESS);
    }

    @Test
    void 최신순이고_size_는_상한을_넘지_못한다() {
        givenSessions(Page.empty());

        service.list(USER_ID, null, null, null, 2, 100_000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionQueryService).findRecords(eq(USER_ID), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(PageRequests.MAX_SIZE);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void 폴더와_검색어를_그대로_넘긴다() {
        givenSessions(Page.empty());
        UUID folderId = UUID.randomUUID();

        service.list(USER_ID, null, folderId, "삼성", null, null);

        verify(sessionQueryService).findRecords(eq(USER_ID), any(), eq(folderId), eq("삼성"), any());
    }

    @Test
    void 기록이_없어도_빈_목록이다() {
        givenSessions(Page.empty());

        PracticeRecordListResponse response = service.list(USER_ID, null, null, null, null, null);

        assertThat(response.records()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @SuppressWarnings("unchecked")
    private Collection<SessionStatus> captureStatuses() {
        ArgumentCaptor<Collection<SessionStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(sessionQueryService).findRecords(eq(USER_ID), statuses.capture(), any(), any(), any());
        return statuses.getValue();
    }

    private void givenSessions(Page<InterviewSession> page) {
        when(sessionQueryService.findRecords(anyString(), any(), any(), any(), any())).thenReturn(page);
        when(sessionQueryService.roundsOf(anyString(), anyList())).thenReturn(Map.of());
    }

    private static InterviewSession session(String id, Company company, SessionStatus status) {
        return InterviewSession.builder().sessionId(id).company(company).jobRole("마케팅")
                .persona(Persona.PRESSURE).questionCount(9).status(status).createdAt(CREATED_AT).build();
    }
}
