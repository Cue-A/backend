package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.config.ReportProperties;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.domain.report.service.ReportProgressStore.ReportProgress;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.AiPoller;
import com.cuea.infrastructure.ai.AiReportResultReader;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.ai.dto.AiReportRetryRequest;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.cuea.infrastructure.websocket.ReportSocketHandler;
import com.cuea.infrastructure.websocket.message.ReportErrorPushMessage;
import com.cuea.infrastructure.websocket.message.ReportProgressStage;
import com.cuea.infrastructure.websocket.message.ReportPushMessage;
import com.cuea.infrastructure.websocket.message.SocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private FakeReportProgressStore progressStore;
    private ReportPoller poller;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        aiPoller = mock(AiPoller.class);
        requestAssembler = mock(ReportRequestAssembler.class);
        reportWriter = mock(ReportWriter.class);
        socketHandler = mock(ReportSocketHandler.class);
        progressStore = new FakeReportProgressStore();
        poller = new ReportPoller(aiClient, aiPoller, new ReportProperties(Duration.ofMinutes(10)),
                requestAssembler, reportWriter, socketHandler, progressStore,
                new AiReportResultReader(JsonMapper.builder().findAndAddModules().build()));

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

    /** 같은 실패를 소켓과 상태 조회가 같은 문구로 보여줘야 합니다. AI 원문은 로그에만 남깁니다. */
    @Test
    void error_메시지는_AI_원문이_아니라_상태_조회와_같은_문구다() throws Exception {
        when(aiPoller.await(anyString(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED, "whisper: empty audio segment"));

        poller.onRequested(event());

        ReportErrorPushMessage error = (ReportErrorPushMessage) pushed();
        assertThat(error.message()).isEqualTo(ReportFailurePolicy.messageOf("STT_FAILED"));
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

    /** 소켓에 늦게 붙은 프론트가 상태 조회 API 로 마지막 단계를 알 수 있어야 합니다. */
    @Test
    void 진행_단계를_보낼_때_상태_조회용으로도_남긴다() {
        when(aiPoller.await(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            Consumer<AiReportTaskStatusResponse> onStageChange = invocation.getArgument(3);
            onStageChange.accept(new AiReportTaskStatusResponse(
                    "processing", "analyzing_content", 0.6, null, null, null));
            assertThat(progressStore.find(PUBLIC_ID.toString()))
                    .contains(new ReportProgress(ReportProgressStage.ANALYZING_CONTENT, 0.6));
            throw new BusinessException(ErrorCode.STT_FAILED);
        });

        poller.onRequested(event());

        verify(socketHandler, times(2)).push(eq(PUBLIC_ID.toString()), any());   // progress + error
    }

    @Test
    void 완료되면_남긴_진행_단계를_지운다() throws Exception {
        AiReportTaskStatusResponse done = done("complete", 68, null);
        when(aiPoller.await(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            progress(invocation, "composing", 0.8);
            return done;
        });
        when(reportWriter.finish(eq(REPORT_ID), any())).thenReturn(finished(ReportStatus.COMPLETED, 68));

        poller.onRequested(event());

        assertThat(progressStore.find(PUBLIC_ID.toString())).isEmpty();
    }

    /**
     * FAILED 가 커밋된 직후 재요청이 들어와 새 폴러가 진행 단계를 남길 수 있습니다.
     * 이전 폴러가 그 뒤에 지우면 새 단계가 사라지므로, DB 쓰기 전에 지워야 합니다.
     */
    @Test
    void 실패하면_FAILED_를_쓰기_전에_진행_단계를_지운다() {
        when(aiPoller.await(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            progress(invocation, "transcribing", 0.1);
            throw new BusinessException(ErrorCode.STT_FAILED);
        });
        doAnswer(invocation -> {
            assertThat(progressStore.find(PUBLIC_ID.toString())).isEmpty();
            return null;
        }).when(reportWriter).fail(REPORT_ID, ErrorCode.STT_FAILED);

        poller.onRequested(event());

        verify(reportWriter).fail(REPORT_ID, ErrorCode.STT_FAILED);
        assertThat(progressStore.find(PUBLIC_ID.toString())).isEmpty();
    }

    /** 재요청은 같은 reportId 라 키가 같습니다. 이전 시도에서 못 지운 값이 새 시도에 보이면 안 됩니다. */
    @Test
    void 시작할_때_이전_시도의_진행_단계를_지운다() throws Exception {
        progressStore.save(PUBLIC_ID.toString(), new ReportProgress(ReportProgressStage.COMPOSING, 0.9), null);
        AiReportTaskStatusResponse done = done("complete", 68, null);
        when(aiPoller.await(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(progressStore.find(PUBLIC_ID.toString())).isEmpty();
            return done;
        });
        when(reportWriter.finish(eq(REPORT_ID), any())).thenReturn(finished(ReportStatus.COMPLETED, 68));

        poller.onRequested(event());

        verify(reportWriter).finish(eq(REPORT_ID), any());
    }

    /**
     * 자동 재시도는 새 task 가 transcribing 부터 다시 돕니다. 첫 시도의 composing 이 남으면 안 됩니다.
     * 재요청(본문 조립 · AI 등록 · DB 갱신)하는 동안에도 보이면 안 되므로 재요청 전에 지웁니다.
     */
    @Test
    void 자동_재시도하면_재요청_전에_첫_시도의_진행_단계를_지운다() throws Exception {
        AiReportTaskStatusResponse done = done("complete", 75, null);
        when(aiPoller.await(anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    progress(invocation, "composing", 0.8);
                    throw new BusinessException(ErrorCode.CONTENT_FAILED);
                })
                .thenAnswer(invocation -> {
                    assertThat(progressStore.find(PUBLIC_ID.toString())).isEmpty();
                    return done;
                });
        when(aiClient.requestReport(eq(SESSION_ID), anyString(), any()))
                .thenAnswer(invocation -> {
                    assertThat(progressStore.find(PUBLIC_ID.toString())).isEmpty();
                    return "task_r2";
                });
        when(reportWriter.finish(eq(REPORT_ID), any())).thenReturn(finished(ReportStatus.COMPLETED, 75));

        poller.onRequested(event());

        verify(reportWriter).finish(eq(REPORT_ID), any());
        verify(reportWriter, never()).fail(any(), any());
    }

    /** 재시도가 성공하면 생성과 똑같이 결과를 통째로 저장하고 report 를 보냅니다. */
    @Test
    void 재시도가_성공하면_결과를_저장하고_report_를_보낸다() throws Exception {
        givenPollResults(done("complete", 74, 65));
        when(reportWriter.finish(eq(REPORT_ID), any())).thenReturn(finished(ReportStatus.COMPLETED, 74));

        poller.onRequested(retryEvent());

        verify(reportWriter).finish(eq(REPORT_ID), any());
        verify(reportWriter, never()).failRetry(any(), any());
        assertThat(pushed()).isInstanceOf(ReportPushMessage.class);
    }

    /** 축 하나 때문에 이미 받은 리포트를 잃지 않게, 리포트를 FAILED 로 만들지 않습니다. */
    @Test
    void 재시도가_실패하면_리포트는_두고_재시도_실패만_남긴다() throws Exception {
        when(aiPoller.await(anyString(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.STT_FAILED));

        poller.onRequested(retryEvent());

        verify(reportWriter).failRetry(REPORT_ID, ErrorCode.STT_FAILED);
        verify(reportWriter, never()).fail(any(), any());
        ReportErrorPushMessage error = (ReportErrorPushMessage) pushed();
        assertThat(error.errorCode()).isEqualTo("STT_FAILED");
    }

    /** 자동 재시도도 생성이 아니라 재시도 엔드포인트를 같은 축으로 부릅니다. */
    @Test
    void 재시도_중_자동_재시도는_재시도_엔드포인트를_같은_축으로_부른다() throws Exception {
        when(aiPoller.await(anyString(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.MEDIA_FETCH_FAILED))
                .thenReturn(done("complete", 74, 65));
        when(aiClient.retryReport(eq(SESSION_ID), anyString(), any())).thenReturn("task_r3");
        when(reportWriter.finish(eq(REPORT_ID), any())).thenReturn(finished(ReportStatus.COMPLETED, 74));

        poller.onRequested(retryEvent());

        ArgumentCaptor<AiReportRetryRequest> body = ArgumentCaptor.forClass(AiReportRetryRequest.class);
        verify(aiClient).retryReport(eq(SESSION_ID), eq("rpt_sess_1_3"), body.capture());
        assertThat(body.getValue().axes()).containsExactly("gaze");
        verify(aiClient, never()).requestReport(anyString(), anyString(), any());
        verify(reportWriter).recordRetry(REPORT_ID, 3, "task_r3");
    }

    /** 점수는 맞아도 상세 조회가 못 읽는 결과는 COMPLETED 로 남기지 않습니다. 남기면 재요청도 막힙니다. */
    @Test
    void 상세_조회가_못_읽는_결과는_저장하지_않고_FAILED() throws Exception {
        ObjectNode result = (ObjectNode) objectMapper.readTree("""
                {"report_status":"complete","overall":{"score":68},
                 "axes":{"content":{"score":72},"speech":{"score":61},"gaze":{"score":null}},
                 "questions":"배열이어야 하는 자리"}
                """);
        givenPollResults(new AiReportTaskStatusResponse("done", null, null, result, null, null));

        poller.onRequested(event());

        verify(reportWriter, never()).finish(any(), any());
        verify(reportWriter).fail(REPORT_ID, ErrorCode.UNEXPECTED_AI_RESPONSE);
    }

    /** aiPoller 가 단계가 바뀔 때 부르는 콜백을 흉내 냅니다. */
    private void progress(InvocationOnMock invocation, String stage, double progress) {
        Consumer<AiReportTaskStatusResponse> onStageChange = invocation.getArgument(3);
        onStageChange.accept(new AiReportTaskStatusResponse("processing", stage, progress, null, null, null));
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
        return new ReportRequestedEvent(REPORT_ID, PUBLIC_ID, SESSION_ID, "task_r1", 1, null);
    }

    /** 1회차가 PARTIAL(시선 실패)로 끝나 시선만 재시도하는 이벤트. */
    private ReportRequestedEvent retryEvent() {
        return new ReportRequestedEvent(REPORT_ID, PUBLIC_ID, SESSION_ID, "task_r2", 2, List.of("gaze"));
    }

    /** 마지막으로 보낸 WebSocket 메시지의 payload. */
    private Object pushed() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SocketMessage<?>> captor = ArgumentCaptor.forClass(SocketMessage.class);
        verify(socketHandler).push(eq(PUBLIC_ID.toString()), captor.capture());
        return captor.getValue().payload();
    }
}
