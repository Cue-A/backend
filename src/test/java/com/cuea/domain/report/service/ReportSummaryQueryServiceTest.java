package com.cuea.domain.report.service;

import com.cuea.domain.report.dto.response.ReportSummary;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.domain.report.repository.ReportSummaryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
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
}
