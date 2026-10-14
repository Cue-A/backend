package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 폴링 종결 판정을 검증합니다. 특히 최신 AI 계약의 실패 상태 {@code error} 가
 * 실패로 인식되어 실제 error_code 가 {@link AiErrorTranslator} 까지 전달되고
 * {@code AI_TIMEOUT} 으로 오인되지 않는지가 핵심입니다.
 */
class AiPollerTest {

    private AiClient aiClient;
    private AiPoller poller;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        AiProperties properties = new AiProperties(
                "http://localhost:8000", "secret",
                Duration.ofSeconds(90), Duration.ofSeconds(60),
                // poll-interval 을 0 으로 두면 processing 없이 바로 다음 응답으로 넘어갑니다.
                Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(10),
                new AiProperties.Mock(false));
        poller = new AiPoller(aiClient, properties, new AiErrorTranslator());
    }

    @Test
    void status_error_는_실패로_인식되어_실제_errorCode_로_예외를_던진다() {
        when(aiClient.getTask(anyString())).thenReturn(new AiTaskStatusResponse(
                AiTaskStatusResponse.STATUS_ERROR, null, null, "STT_FAILED", "음성을 인식하지 못했습니다"));

        assertThatThrownBy(() -> poller.await("task_1", Duration.ofSeconds(90)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                // AI_TIMEOUT 이 아니라 실제 코드가 전달돼야 한다.
                .isEqualTo(ErrorCode.STT_FAILED);
    }

    @Test
    void 레거시_failed_상태도_실패로_인식한다() {
        when(aiClient.getTask(anyString())).thenReturn(new AiTaskStatusResponse(
                AiTaskStatusResponse.STATUS_FAILED, null, null, "LLM_FAILED", "질문 생성 실패"));

        assertThatThrownBy(() -> poller.await("task_1", Duration.ofSeconds(90)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.LLM_FAILED);
    }

    @Test
    void done_이면_결과를_그대로_돌려준다() {
        AiQuestionResult result = new AiQuestionResult(
                AiQuestionResult.TYPE_QUESTION, "q_1", null, "질문", null,
                "지원동기", "L1", 1, 6, 0, 2, false, false, null);
        when(aiClient.getTask(anyString())).thenReturn(
                new AiTaskStatusResponse(AiTaskStatusResponse.STATUS_DONE, null, result, null, null));

        AiTaskStatusResponse status = poller.await("task_1", Duration.ofSeconds(90));

        assertThat(status.isDone()).isTrue();
        assertThat(status.result()).isEqualTo(result);
    }

    // ── 리포트 작업 ───────────────────────────────────────────

    @Test
    void 리포트_작업도_done_이면_원본_결과를_그대로_돌려준다() {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("report_status", "complete");
        when(aiClient.getReportTask(anyString())).thenReturn(reportStatus("done", null, null, result, null));

        AiReportTaskStatusResponse status = poller.await(
                "task_r1", Duration.ofMinutes(10), aiClient::getReportTask, null);

        assertThat(status.result().path("report_status").asText()).isEqualTo("complete");
    }

    /** 리포트 계약 코드가 ErrorCode 에 없으면 AI_UNAVAILABLE 로 뭉개져 재시도 분기를 못 탑니다. */
    @Test
    void 리포트_실패_코드가_그대로_전달된다() {
        when(aiClient.getReportTask(anyString())).thenReturn(
                reportStatus("error", null, null, null, "CONTENT_FAILED"),
                reportStatus("error", null, null, null, "MEDIA_FETCH_FAILED"));

        assertThatThrownBy(() -> poller.await("task_r1", Duration.ofMinutes(10), aiClient::getReportTask, null))
                .extracting("errorCode").isEqualTo(ErrorCode.CONTENT_FAILED);
        assertThatThrownBy(() -> poller.await("task_r1", Duration.ofMinutes(10), aiClient::getReportTask, null))
                .extracting("errorCode").isEqualTo(ErrorCode.MEDIA_FETCH_FAILED);
    }

    /** stage 가 바뀔 때만 한 번씩, 그 시점의 progress 와 함께 알립니다. */
    @Test
    void stage_가_바뀔_때만_응답_전체로_알린다() {
        when(aiClient.getReportTask(anyString())).thenReturn(
                reportStatus("processing", "transcribing", 0.1, null, null),
                reportStatus("processing", "transcribing", 0.2, null, null),
                reportStatus("processing", "analyzing_content", 0.6, null, null),
                reportStatus("done", null, null, JsonNodeFactory.instance.objectNode(), null));
        List<AiReportTaskStatusResponse> notified = new ArrayList<>();

        poller.await("task_r1", Duration.ofMinutes(10), aiClient::getReportTask, notified::add);

        assertThat(notified).extracting(AiReportTaskStatusResponse::stage)
                .containsExactly("transcribing", "analyzing_content");
        assertThat(notified).extracting(AiReportTaskStatusResponse::progress)
                .containsExactly(0.1, 0.6);
    }

    private AiReportTaskStatusResponse reportStatus(String status, String stage, Double progress,
                                                    ObjectNode result, String errorCode) {
        return new AiReportTaskStatusResponse(status, stage, progress, result, errorCode, null);
    }
}
