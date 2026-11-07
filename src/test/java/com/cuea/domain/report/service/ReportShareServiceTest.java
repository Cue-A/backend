package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.report.dto.response.ReportShareInfo;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공유 링크 발급 · 해제. 끝난 본인 리포트만 공유되고, 이미 공유 중이면 같은 링크를 주는지 봅니다.
 * 두 번 눌렀을 때 링크가 하나만 생기는 DB 조건은 {@code ReportRepositoryTest} 가 봅니다.
 */
class ReportShareServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID PUBLIC_ID = UUID.randomUUID();
    private static final UUID SHARE_ID = UUID.randomUUID();
    private static final OffsetDateTime SHARED_AT = OffsetDateTime.parse("2026-11-08T10:00:00Z");

    private ReportRepository reportRepository;
    private ReportShareService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        service = new ReportShareService(new ReportFinder(reportRepository), reportRepository);
    }

    @Test
    void 끝난_리포트를_공유하면_reportId_와_다른_새_토큰을_발급한다() {
        AtomicReference<UUID> written = new AtomicReference<>();
        when(reportRepository.share(eq(10L), any(), any())).thenAnswer(invocation -> {
            written.set(invocation.getArgument(1));
            return 1;
        });
        when(reportRepository.findByPublicIdAndSession_User_UserId(PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(report(ReportStatus.PARTIAL, null, null)))
                .thenAnswer(invocation -> Optional.of(report(ReportStatus.PARTIAL, written.get(), SHARED_AT)));

        ReportShareInfo share = service.share(USER_ID, PUBLIC_ID.toString());

        assertThat(written.get()).isNotNull().isNotEqualTo(PUBLIC_ID);
        assertThat(share.shareId()).isEqualTo(written.get().toString());
        assertThat(share.sharedAt()).isEqualTo(SHARED_AT);
    }

    @Test
    void 이미_공유_중이면_새로_쓰지_않고_같은_링크를_준다() {
        givenReport(report(ReportStatus.COMPLETED, SHARE_ID, SHARED_AT));

        ReportShareInfo share = service.share(USER_ID, PUBLIC_ID.toString());

        assertThat(share.shareId()).isEqualTo(SHARE_ID.toString());
        assertThat(share.sharedAt()).isEqualTo(SHARED_AT);
        verify(reportRepository, never()).share(anyLong(), any(), any());
    }

    /** 공유를 두 번 눌러 다른 요청이 먼저 썼으면, 내가 만든 토큰이 아니라 DB 에 남은 링크를 줍니다. */
    @Test
    void 다른_요청이_먼저_공유했으면_그_링크를_준다() {
        when(reportRepository.share(eq(10L), any(), any())).thenReturn(0);
        when(reportRepository.findByPublicIdAndSession_User_UserId(PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(report(ReportStatus.PARTIAL, null, null)))
                .thenReturn(Optional.of(report(ReportStatus.PARTIAL, SHARE_ID, SHARED_AT)));

        ReportShareInfo share = service.share(USER_ID, PUBLIC_ID.toString());

        assertThat(share.shareId()).isEqualTo(SHARE_ID.toString());
    }

    @Test
    void 분석_중이거나_실패한_리포트는_공유하지_않는다() {
        givenReport(report(ReportStatus.PROCESSING, null, null));
        assertErrorCode(() -> service.share(USER_ID, PUBLIC_ID.toString()), ErrorCode.REPORT_NOT_READY);

        givenReport(report(ReportStatus.FAILED, null, null));
        assertErrorCode(() -> service.share(USER_ID, PUBLIC_ID.toString()), ErrorCode.REPORT_FAILED);

        verify(reportRepository, never()).share(anyLong(), any(), any());
    }

    @Test
    void 남의_리포트거나_UUID_가_아니면_REPORT_NOT_FOUND() {
        when(reportRepository.findByPublicIdAndSession_User_UserId(any(), anyString()))
                .thenReturn(Optional.empty());

        assertErrorCode(() -> service.share(USER_ID, PUBLIC_ID.toString()), ErrorCode.REPORT_NOT_FOUND);
        assertErrorCode(() -> service.share(USER_ID, "not-a-uuid"), ErrorCode.REPORT_NOT_FOUND);
        assertErrorCode(() -> service.unshare(USER_ID, PUBLIC_ID.toString()), ErrorCode.REPORT_NOT_FOUND);
        assertErrorCode(() -> service.unshare(USER_ID, "not-a-uuid"), ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    void 해제하면_토큰과_공유_시각을_비운다() {
        Report shared = report(ReportStatus.PARTIAL, SHARE_ID, SHARED_AT);
        givenReport(shared);

        service.unshare(USER_ID, PUBLIC_ID.toString());

        assertThat(shared.getShareId()).isNull();
        assertThat(shared.getSharedAt()).isNull();
    }

    @Test
    void 공유하지_않은_리포트를_해제해도_오류가_아니다() {
        Report notShared = report(ReportStatus.PARTIAL, null, null);
        givenReport(notShared);

        service.unshare(USER_ID, PUBLIC_ID.toString());

        assertThat(notShared.getShareId()).isNull();
    }

    private void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private void givenReport(Report report) {
        when(reportRepository.findByPublicIdAndSession_User_UserId(PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(report));
    }

    private static Report report(ReportStatus status, UUID shareId, OffsetDateTime sharedAt) {
        InterviewSession session = InterviewSession.builder().sessionId("sess_1").build();
        return Report.builder().reportId(10L).publicId(PUBLIC_ID).session(session)
                .status(status).attempt(1).reportData(Map.of("report_status", "partial"))
                .shareId(shareId).sharedAt(sharedAt).build();
    }
}
