package com.cuea.domain.report.service;

import com.cuea.domain.report.dto.response.ReportSummary;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.RecentReportRow;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.domain.report.repository.ReportSummaryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportSummaryQueryServiceTest {

    private ReportRepository reportRepository;
    private ReportSummaryQueryService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        service = new ReportSummaryQueryService(reportRepository);
    }

    @Test
    void 세션_ID_로_요약을_묶어_준다() {
        UUID publicId = UUID.randomUUID();
        when(reportRepository.findSummaries("user-1", List.of("sess_1", "sess_2")))
                .thenReturn(List.of(new ReportSummaryRow("sess_1", publicId, ReportStatus.COMPLETED, 84)));

        assertThat(service.findBySessionIds("user-1", List.of("sess_1", "sess_2")))
                .containsOnlyKeys("sess_1")
                .containsEntry("sess_1", new ReportSummary(publicId.toString(), ReportStatus.COMPLETED, 84));
    }

    @Test
    void 세션이_없으면_조회하지_않는다() {
        assertThat(service.findBySessionIds("user-1", List.of())).isEmpty();

        verify(reportRepository, never()).findSummaries(anyString(), anyCollection());
    }

    @Test
    void 최근_열람가능_리포트는_COMPLETED_PARTIAL_만_limit_만큼_요청한다() {
        RecentReportRow row = new RecentReportRow(
                UUID.randomUUID(), "네이버", "백엔드", 9, ReportStatus.COMPLETED, 92,
                OffsetDateTime.parse("2026-10-28T09:00:00Z"));
        when(reportRepository.findRecentViewable(anyString(), anyCollection(), any())).thenReturn(List.of(row));

        List<RecentReportRow> result = service.findRecentViewable("user-1", 3);

        assertThat(result).containsExactly(row);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ReportStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reportRepository).findRecentViewable(eq("user-1"), statuses.capture(), pageable.capture());
        assertThat(statuses.getValue()).containsExactly(ReportStatus.COMPLETED, ReportStatus.PARTIAL);
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(3);
    }
}
