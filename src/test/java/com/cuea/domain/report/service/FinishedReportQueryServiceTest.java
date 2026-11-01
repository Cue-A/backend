package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportRetryStatus;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 회차 비교가 읽어가는 끝난 리포트. 상태 규칙은 상세 조회와 같아야 합니다. */
class FinishedReportQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID PUBLIC_ID = UUID.randomUUID();
    private static final Map<String, Object> REPORT_DATA = Map.of("report_status", "partial");

    private ReportRepository reportRepository;
    private FinishedReportQueryService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        service = new FinishedReportQueryService(new ReportFinder(reportRepository), reportRepository);
    }

    @Test
    void 끝난_리포트는_원본과_함께_준다() {
        givenReport(report("sess_1", ReportStatus.PARTIAL, REPORT_DATA));

        FinishedReport finished = service.getFinished(USER_ID, PUBLIC_ID.toString());

        assertThat(finished).isEqualTo(
                new FinishedReport(PUBLIC_ID.toString(), "sess_1", ReportStatus.PARTIAL, REPORT_DATA));
    }

    /** 실패 축 재시도 중에도 리포트는 PARTIAL 결과 그대로라 비교할 수 있습니다. */
    @Test
    void 재시도_중인_PARTIAL_도_끝난_리포트다() {
        Report retrying = Report.builder().reportId(1L).publicId(PUBLIC_ID)
                .session(InterviewSession.builder().sessionId("sess_1").build())
                .status(ReportStatus.PARTIAL).retryStatus(ReportRetryStatus.PROCESSING).attempt(2)
                .reportData(REPORT_DATA).build();
        givenReport(retrying);

        assertThat(service.getFinished(USER_ID, PUBLIC_ID.toString()).status()).isEqualTo(ReportStatus.PARTIAL);
    }

    @Test
    void 분석_중이면_REPORT_NOT_READY() {
        givenReport(report("sess_1", ReportStatus.PROCESSING, null));

        assertErrorCode(ErrorCode.REPORT_NOT_READY);
    }

    @Test
    void 실패한_리포트면_REPORT_FAILED() {
        givenReport(report("sess_1", ReportStatus.FAILED, null));

        assertErrorCode(ErrorCode.REPORT_FAILED);
    }

    @Test
    void 없거나_남의_리포트면_REPORT_NOT_FOUND() {
        when(reportRepository.findByPublicIdAndSession_User_UserId(PUBLIC_ID, USER_ID)).thenReturn(Optional.empty());

        assertErrorCode(ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    void 끝났는데_원본이_없으면_우리_쪽_문제라_INTERNAL_ERROR() {
        givenReport(report("sess_1", ReportStatus.COMPLETED, null));

        assertErrorCode(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 세션_ID_로_끝난_리포트를_묶어_준다() {
        List<String> sessionIds = List.of("sess_1", "sess_2");
        List<ReportStatus> finished = List.of(ReportStatus.COMPLETED, ReportStatus.PARTIAL);
        when(reportRepository.findAllBySessionIds(USER_ID, sessionIds, finished))
                .thenReturn(List.of(report("sess_2", ReportStatus.COMPLETED, REPORT_DATA)));

        assertThat(service.findFinishedBySessionIds(USER_ID, sessionIds))
                .containsOnlyKeys("sess_2")
                .hasEntrySatisfying("sess_2", r -> assertThat(r.status()).isEqualTo(ReportStatus.COMPLETED));
    }

    @Test
    void 세션이_없으면_조회하지_않는다() {
        assertThat(service.findFinishedBySessionIds(USER_ID, List.of())).isEmpty();

        verify(reportRepository, never()).findAllBySessionIds(anyString(), anyCollection(), anyCollection());
    }

    private void assertErrorCode(ErrorCode expected) {
        assertThatThrownBy(() -> service.getFinished(USER_ID, PUBLIC_ID.toString()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }

    private void givenReport(Report report) {
        when(reportRepository.findByPublicIdAndSession_User_UserId(PUBLIC_ID, USER_ID)).thenReturn(Optional.of(report));
    }

    private static Report report(String sessionId, ReportStatus status, Map<String, Object> reportData) {
        return Report.builder().reportId(1L).publicId(PUBLIC_ID)
                .session(InterviewSession.builder().sessionId(sessionId).build())
                .status(status).attempt(1).reportData(reportData).build();
    }
}
