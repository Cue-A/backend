package com.cuea.domain.report.service;

import com.cuea.domain.growth.badge.ReportScoredEvent;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.domain.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReportWriter#finish} 가 RISING 배지 트리거 이벤트({@link ReportScoredEvent})를
 * <b>점수 확정 리포트에만</b> 발행하는지 검증합니다(Issue #131 리뷰 보완).
 *
 * <p>세션 완료 이벤트만으로는 리포트 점수가 아직 없어 RISING 이 영구 누락될 수 있어, 점수가
 * 확정(COMPLETED·PARTIAL + score_total not null)되는 이 시점에 별도 이벤트를 냅니다. 이 이벤트가
 * AFTER_COMMIT @Async 리스너(BadgeEventListener)로 흘러 RISING 재평가를 트리거합니다. 발행
 * 자체는 짧은 트랜잭션의 동기 호출이라 mock 으로 결정적으로 검증할 수 있습니다(Thread.sleep 불필요).
 */
class ReportWriterTest {

    private static final long REPORT_ID = 10L;
    private static final String USER_ID = "user_1";

    private ReportRepository reportRepository;
    private ApplicationEventPublisher eventPublisher;
    private ReportWriter writer;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        writer = new ReportWriter(reportRepository, eventPublisher, new ObjectMapper());

        User user = mock(User.class);
        when(user.getUserId()).thenReturn(USER_ID);
        InterviewSession session = mock(InterviewSession.class);
        when(session.getUser()).thenReturn(user);

        Report report = Report.processing(session, "task_1");
        when(reportRepository.findByIdForInternal(REPORT_ID)).thenReturn(Optional.of(report));
    }

    private ReportResult result(ReportStatus status, Integer scoreTotal) {
        return new ReportResult(status, scoreTotal, scoreTotal, scoreTotal, scoreTotal,
                JsonNodeFactory.instance.objectNode());
    }

    @Test
    void COMPLETED_에_점수가_있으면_ReportScoredEvent_를_소유자로_발행한다() {
        writer.finish(REPORT_ID, result(ReportStatus.COMPLETED, 80));

        verify(eventPublisher).publishEvent(new ReportScoredEvent(USER_ID));
    }

    @Test
    void PARTIAL_에_점수가_있으면_ReportScoredEvent_를_발행한다() {
        writer.finish(REPORT_ID, result(ReportStatus.PARTIAL, 55));

        verify(eventPublisher).publishEvent(new ReportScoredEvent(USER_ID));
    }

    @Test
    void 점수_0_도_유효한_점수라_ReportScoredEvent_를_발행한다() {
        writer.finish(REPORT_ID, result(ReportStatus.COMPLETED, 0));

        verify(eventPublisher).publishEvent(new ReportScoredEvent(USER_ID));
    }

    @Test
    void scoreTotal_이_null_이면_ReportScoredEvent_를_발행하지_않는다() {
        writer.finish(REPORT_ID, result(ReportStatus.PARTIAL, null));

        verify(eventPublisher, never()).publishEvent(any(ReportScoredEvent.class));
    }
}
