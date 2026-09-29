package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.config.ReportProperties;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.AiPoller;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.cuea.infrastructure.websocket.ReportSocketHandler;
import com.cuea.infrastructure.websocket.message.ReportErrorPushMessage;
import com.cuea.infrastructure.websocket.message.ReportPushMessage;
import com.cuea.infrastructure.websocket.message.SocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 백그라운드 구간(⑦~⑪). 어떤 경로로 끝나든 리포트가 PROCESSING 으로 남지 않고,
 * 프론트가 report 또는 error 하나를 받는지가 핵심입니다.
 */
class ReportPollerTest {

    private static final Long REPORT_ID = 10L;
    private static final UUID PUBLIC_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess_1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiClient aiClient;
    private AiPoller aiPoller;
    private ReportRequestAssembler requestAssembler;
    private ReportWriter reportWriter;
    private ReportSocketHandler socketHandler;
    private ReportPoller poller;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        aiPoller = mock(AiPoller.class);
        requestAssembler = mock(ReportRequestAssembler.class);
        reportWriter = mock(ReportWriter.class);
        socketHandler = mock(ReportSocketHandler.class);
        poller = new ReportPoller(aiClient, aiPoller, new ReportProperties(Duration.ofMinutes(10)),
                requestAssembler, reportWriter, socketHandler);

        when(requestAssembler.build(SESSION_ID))
                .thenReturn(new AiReportRequest("friendly", "백엔드 개발", null, null, List.of()));
    }

    @Test
    void complete_면_점수를_저장하고_report_를_보낸다() throws Exception {
        givenPollResults(done("complete", 68, null));
        when(reportWriter.finish(eq(REPORT_ID), any())).thenReturn(finished(ReportStatus.COMPLETED, 68));

        poller.onRequested(event());

        ArgumentCaptor<ReportResult> result = ArgumentCaptor.forClass(ReportResult.class);
        verify(reportWriter).finish(eq(REPORT_ID), result.capture());
        assertThat(result.getValue().status()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(result.getValue().scoreTotal()).isEqualTo(68);
        assertThat(pushed()).isInstanceOf(ReportPushMessage.class);
    }

    @Test
    void partial_이면_PARTIAL_로_저장하고_실패_축_점수는_null() throws Exception {
        givenPollResults(done("partial", 70, null));
        when(reportWriter.finish(eq(REPORT_ID), any())).thenReturn(finished(ReportStatus.PARTIAL, 70));

        poller.onRequested(event());

        ArgumentCaptor<ReportResult> result = ArgumentCaptor.forClass(ReportResult.class);
        verify(reportWriter).finish(eq(REPORT_ID), result.capture());
        assertThat(result.getValue().status()).isEqualTo(ReportStatus.PARTIAL);
        assertThat(result.getValue().scoreGaze()).isNull();
    }

    /** 같은 키면 AI 가 실패한 task 를 돌려주므로, 재시도는 시도 번호를 올린 새 키여야 합니다. */
    @Test
    void CONTENT_FAILED_는_새_키로_한_번_다시_요청해_성공하면_완료() throws Exception {
        when(aiPoller.await(anyString(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.CONTENT_FAILED))
                .thenReturn(done("complete", 75, null));
        when(aiClient.requestReport(eq(SESSION_ID), anyString(), any())).thenReturn("task_r2");
        when(reportWriter.finish(eq(REPORT_ID), any())).thenReturn(finished(ReportStatus.COMPLETED, 75));

        poller.onRequested(event());

        verify(aiClient).requestReport(eq(SESSION_ID), eq("rpt_sess_1_2"), any());
        verify(reportWriter).recordRetry(REPORT_ID, 2, "task_r2");
        verify(requestAssembler).build(SESSION_ID);   // presigned URL 을 새로 받으려고 다시 조립
        verify(reportWriter).finish(eq(REPORT_ID), any());
        verify(reportWriter, never()).fail(any(), any());
    }

    @Test
    void 자동_재시도는_한_번뿐이고_또_실패하면_FAILED_retryable() throws Exception {
        when(aiPoller.await(anyString(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.MEDIA_FETCH_FAILED));
        when(aiClient.requestReport(eq(SESSION_ID), anyString(), any())).thenReturn("task_r2");

        poller.onRequested(event());

        verify(aiClient, times(1)).requestReport(anyString(), anyString(), any());
        verify(reportWriter).fail(REPORT_ID, ErrorCode.MEDIA_FETCH_FAILED);
        ReportErrorPushMessage error = (ReportErrorPushMessage) pushed();
        assertThat(error.errorCode()).isEqualTo("MEDIA_FETCH_FAILED");
        assertThat(error.retryable()).isTrue();
    }

    @Test
    void STT_FAILED_는_재시도_없이_FAILED_이고_retryable_false() throws Exception {
        when(aiPoller.await(anyString(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED));

        poller.onRequested(event());

        verify(aiClient, never()).requestReport(anyString(), anyString(), any());
        verify(reportWriter).fail(REPORT_ID, ErrorCode.STT_FAILED);
        assertThat(((ReportErrorPushMessage) pushed()).retryable()).isFalse();
    }

    @Test
    void 십분을_넘기면_AI_TIMEOUT_으로_FAILED() throws Exception {
        when(aiPoller.await(anyString(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_TIMEOUT));

        poller.onRequested(event());

        verify(reportWriter).fail(REPORT_ID, ErrorCode.AI_TIMEOUT);
        assertThat(((ReportErrorPushMessage) pushed()).retryable()).isTrue();
    }

    @Test
    void 재시도_요청_자체가_실패해도_FAILED_로_정리한다() throws Exception {
        when(aiPoller.await(anyString(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.CONTENT_FAILED));
        when(aiClient.requestReport(anyString(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));

        poller.onRequested(event());

        verify(reportWriter).fail(REPORT_ID, ErrorCode.AI_UNAVAILABLE);
    }

    /** @Async void 에서 예외가 새면 리포트가 PROCESSING 으로 영구히 남습니다. */
    @Test
    void 결과_저장이_예기치_않게_실패해도_FAILED_로_정리한다() throws Exception {
        givenPollResults(done("complete", 68, null));
        when(reportWriter.finish(eq(REPORT_ID), any())).thenThrow(new IllegalStateException("DB 끊김"));

        poller.onRequested(event());

        verify(reportWriter).fail(REPORT_ID, ErrorCode.INTERNAL_ERROR);
        assertThat(pushed()).isInstanceOf(ReportErrorPushMessage.class);
    }

    @Test
    void 결과가_계약과_다르면_UNEXPECTED_AI_RESPONSE() throws Exception {
        givenPollResults(new AiReportTaskStatusResponse("done", null, null, null, null, null));

        poller.onRequested(event());

        verify(reportWriter).fail(REPORT_ID, ErrorCode.UNEXPECTED_AI_RESPONSE);
    }

    private void givenPollResults(AiReportTaskStatusResponse response) {
        when(aiPoller.await(anyString(), any(), any(), any())).thenReturn(response);
    }

    private AiReportTaskStatusResponse done(String reportStatus, int total, Integer gaze) throws Exception {
        ObjectNode result = (ObjectNode) objectMapper.readTree("""
                {"report_status":"%s","overall":{"score":%d},
                 "axes":{"content":{"score":72},"speech":{"score":61},"gaze":{"score":%s}}}
                """.formatted(reportStatus, total, gaze == null ? "null" : gaze));
        return new AiReportTaskStatusResponse("done", null, null, result, null, null);
    }

    private Report finished(ReportStatus status, int total) {
        return Report.builder().reportId(REPORT_ID).publicId(PUBLIC_ID)
                .status(status).attempt(1).scoreTotal(total).build();
    }

    private ReportRequestedEvent event() {
        return new ReportRequestedEvent(REPORT_ID, PUBLIC_ID, SESSION_ID, "task_r1", 1);
    }

    /** 마지막으로 보낸 WebSocket 메시지의 payload. */
    private Object pushed() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(PUBLIC_ID.toString()), captor.capture());
        return captor.getValue().payload();
    }
}
