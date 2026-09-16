package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
