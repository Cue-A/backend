package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.report.dto.response.ReportStatusResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.domain.report.service.ReportProgressStore.ReportProgress;
import com.cuea.infrastructure.websocket.message.ReportProgressStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 상태는 DB 기준이고, 진행 단계는 PROCESSING 일 때만 붙는지를 봅니다.
 */
class ReportStatusServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID PUBLIC_ID = UUID.randomUUID();

    private ReportRepository reportRepository;
    private FakeReportProgressStore progressStore;
    private ReportStatusService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        progressStore = new FakeReportProgressStore();
        service = new ReportStatusService(reportRepository, progressStore);
    }

    @Test
    void PROCESSING_이면_마지막_진행_단계를_붙인다() {
        givenReport(report(ReportStatus.PROCESSING, null));
        progressStore.save(PUBLIC_ID.toString(),
                new ReportProgress(ReportProgressStage.ANALYZING_CONTENT, 0.6), null);

        ReportStatusResponse response = service.getStatus(USER_ID, PUBLIC_ID.toString());

        assertThat(response.status()).isEqualTo(ReportStatus.PROCESSING);
        assertThat(response.stage()).isEqualTo(ReportProgressStage.ANALYZING_CONTENT);
        assertThat(response.progress()).isEqualTo(0.6);
        assertThat(response.errorCode()).isNull();
        assertThat(response.retryable()).isNull();
    }

    @Test
    void PROCESSING_인데_아직_진행_알림_전이면_stage_는_null() {
        givenReport(report(ReportStatus.PROCESSING, null));

        ReportStatusResponse response = service.getStatus(USER_ID, PUBLIC_ID.toString());

        assertThat(response.status()).isEqualTo(ReportStatus.PROCESSING);
        assertThat(response.stage()).isNull();
        assertThat(response.progress()).isNull();
    }

    /** 끝날 때 진행 단계를 지우지 못했어도 완료된 리포트에 붙어 나가면 안 됩니다. */
    @Test
    void 완료된_리포트에는_남은_진행_단계가_붙지_않는다() {
        givenReport(report(ReportStatus.COMPLETED, null));
        progressStore.save(PUBLIC_ID.toString(),
                new ReportProgress(ReportProgressStage.COMPOSING, 0.9), null);

        ReportStatusResponse response = service.getStatus(USER_ID, PUBLIC_ID.toString());

        assertThat(response.status()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(response.stage()).isNull();
        assertThat(response.progress()).isNull();
    }

    @Test
    void FAILED_면_errorCode_와_retryable_을_준다() {
        givenReport(report(ReportStatus.FAILED, "AI_TIMEOUT"));

        ReportStatusResponse response = service.getStatus(USER_ID, PUBLIC_ID.toString());

        assertThat(response.status()).isEqualTo(ReportStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(response.retryable()).isTrue();
    }

    @Test
    void STT_FAILED_는_retryable_false() {
        givenReport(report(ReportStatus.FAILED, "STT_FAILED"));

        assertThat(service.getStatus(USER_ID, PUBLIC_ID.toString()).retryable()).isFalse();
    }

    @Test
    void 지금_없는_옛_errorCode_도_터지지_않고_retryable_false() {
        givenReport(report(ReportStatus.FAILED, "SOMETHING_REMOVED"));

        assertThat(service.getStatus(USER_ID, PUBLIC_ID.toString()).retryable()).isFalse();
    }

    @Test
    void 대문자로_넣어도_응답의_reportId_는_저장된_값() {
        givenReport(report(ReportStatus.COMPLETED, null));

        ReportStatusResponse response = service.getStatus(USER_ID, PUBLIC_ID.toString().toUpperCase());

        assertThat(response.reportId()).isEqualTo(PUBLIC_ID.toString());
    }

    /** 남의 리포트도 소유자 조건이 걸린 조회에서 빈 값이라 여기로 옵니다. */
    @Test
    void 없거나_남의_리포트면_REPORT_NOT_FOUND() {
        when(reportRepository.findByPublicIdAndSession_User_UserId(any(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(USER_ID, PUBLIC_ID.toString()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    void UUID_가_아니면_조회하지_않고_REPORT_NOT_FOUND() {
        assertThatThrownBy(() -> service.getStatus(USER_ID, "not-a-uuid"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
        verify(reportRepository, never()).findByPublicIdAndSession_User_UserId(any(), anyString());
    }

    private void givenReport(Report report) {
        when(reportRepository.findByPublicIdAndSession_User_UserId(PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(report));
    }

    private Report report(ReportStatus status, String errorCode) {
        return Report.builder().reportId(10L).publicId(PUBLIC_ID)
                .status(status).attempt(1).errorCode(errorCode).build();
    }
}
