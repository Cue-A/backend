package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.dto.AiReportAnswer;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.ai.dto.AiReportRetryRequest;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 로컬 기본값이 mock 이라, mock 이 실제 AI 계약과 다르게 굴면 리포트 흐름을 로컬에서
 * 검증할 수 없습니다. 계약의 세 가지(2문항 미만 422, 멱등 키, processing 후 done)와
 * 더미 서버와 같은 실패 트리거(content_fail · fail)를 봅니다.
 */
class MockAiClientReportTest {

    private final MockAiClient client = new MockAiClient();

    @Test
    void 되묻기를_뺀_답변이_2개_미만이면_REPORT_TOO_SHORT() {
        AiReportRequest request = request(List.of(answer("q_1", "question", null), answer("q_1r", "reask", null)));

        assertThatThrownBy(() -> client.requestReport("sess_1", "rpt_sess_1_1", request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REPORT_TOO_SHORT);
    }

    @Test
    void 같은_멱등_키면_같은_task_id_다른_키면_새_task_id() {
        AiReportRequest request = request(List.of(answer("q_1", "question", null), answer("q_2", "followup", null)));

        String first = client.requestReport("sess_1", "rpt_sess_1_1", request);
        String again = client.requestReport("sess_1", "rpt_sess_1_1", request);
        String retried = client.requestReport("sess_1", "rpt_sess_1_2", request);

        assertThat(again).isEqualTo(first);
        assertThat(retried).isNotEqualTo(first);
    }

    @Test
    void processing_을_거친_뒤_complete_리포트를_주고_영상이_없으면_시선은_skipped() {
        String taskId = client.requestReport("sess_1", "rpt_sess_1_1",
                request(List.of(answer("q_1", "question", null), answer("q_2", "followup", null))));

        AiReportTaskStatusResponse status = client.getReportTask(taskId);
        assertThat(status.status()).isEqualTo("processing");
        assertThat(status.stage()).isEqualTo("transcribing");

        while (!status.isDone()) {
            status = client.getReportTask(taskId);
        }
        assertThat(status.result().path("report_status").asText()).isEqualTo("complete");
        assertThat(status.result().path("axes").path("gaze").path("status").asText()).isEqualTo("skipped");
    }

    @Test
    void 재시도에_axes_가_비면_INVALID_REQUEST() {
        AiReportRequest base = request(List.of(answer("q_1", "question", null), answer("q_2", "followup", null)));

        assertThatThrownBy(() -> client.retryReport("sess_1", "rpt_sess_1_2", AiReportRetryRequest.of(List.of(), base)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    /** 다시 계산하는 축만 마커를 다시 봅니다. URL 에서 fail 을 지우면 그 축이 살아납니다. */
    @Test
    void 재시도는_요청한_축만_다시_계산해_전체_리포트를_준다() {
        AiReportRequest fixed = request(List.of(
                answer("q_1", "question", "https://s3/audio/fail.webm", "https://s3/video/ok.mp4"),
                answer("q_2", "followup", null)));

        String taskId = client.retryReport("sess_1", "rpt_sess_1_2", AiReportRetryRequest.of(List.of("gaze"), fixed));
        JsonNode result = pollToEnd(taskId).result();

        // 말하기는 요청하지 않았으므로 녹음 URL 에 fail 이 있어도 이전처럼 성공한 것으로 둡니다.
        assertThat(result.path("report_status").asText()).isEqualTo("complete");
        assertThat(result.path("axes").path("gaze").path("status").asText()).isEqualTo("ok");
        assertThat(result.path("axes").path("speech").path("status").asText()).isEqualTo("ok");
    }

    @Test
    void 재시도도_URL_이_그대로면_같은_축이_다시_실패한다() {
        AiReportRequest same = request(List.of(
                answer("q_1", "question", "https://s3/audio/a.webm", "https://s3/video/fail.mp4"),
                answer("q_2", "followup", null)));

        String taskId = client.retryReport("sess_1", "rpt_sess_1_2", AiReportRetryRequest.of(List.of("gaze"), same));

        assertThat(pollToEnd(taskId).result().path("overall").path("axes_failed").toString()).isEqualTo("[\"gaze\"]");
    }

    @Test
    void 영상이_있으면_시선_축도_점수가_나온다() {
        String taskId = client.requestReport("sess_1", "rpt_sess_1_1",
                request(List.of(answer("q_1", "question", "https://s3/v1.mp4"), answer("q_2", "followup", null))));

        AiReportTaskStatusResponse status = client.getReportTask(taskId);
        while (!status.isDone()) {
            status = client.getReportTask(taskId);
        }
        assertThat(status.result().path("axes").path("gaze").path("status").asText()).isEqualTo("ok");
    }

    @Test
    void 녹음_URL_에_content_fail_이_있으면_processing_뒤_CONTENT_FAILED_로_끝난다() {
        String taskId = client.requestReport("sess_1", "rpt_sess_1_1", request(List.of(
                answer("q_1", "question", "https://s3/audio/content_fail.webm", null),
                answer("q_2", "followup", null))));

        AiReportTaskStatusResponse status = pollToEnd(taskId);

        assertThat(status.isFailed()).isTrue();
        assertThat(status.errorCode()).isEqualTo("CONTENT_FAILED");
        assertThat(status.result()).isNull();
    }

    /** 더미 서버와 같습니다. URL 이 그대로면 새 키로 다시 요청해도 또 실패합니다. */
    @Test
    void content_fail_은_새_키로_다시_요청해도_다시_실패한다() {
        AiReportRequest request = request(List.of(
                answer("q_1", "question", "https://s3/audio/content_fail.webm", null),
                answer("q_2", "followup", null)));
        client.requestReport("sess_1", "rpt_sess_1_1", request);

        String retried = client.requestReport("sess_1", "rpt_sess_1_2", request);

        assertThat(pollToEnd(retried).errorCode()).isEqualTo("CONTENT_FAILED");
    }

    @Test
    void 녹음_URL_에_fail_이_있으면_말하기_축만_실패한_partial() {
        String taskId = client.requestReport("sess_1", "rpt_sess_1_1", request(List.of(
                answer("q_1", "question", "https://s3/audio/FAIL.webm", "https://s3/v1.mp4"),
                answer("q_2", "followup", null))));

        JsonNode result = pollToEnd(taskId).result();

        assertThat(result.path("report_status").asText()).isEqualTo("partial");
        assertThat(result.path("overall").path("partial").asBoolean()).isTrue();
        assertThat(result.path("axes").path("speech").path("status").asText()).isEqualTo("failed");
        assertThat(result.path("axes").path("speech").path("error_code").asText()).isEqualTo("SPEECH_FAILED");
        assertThat(result.path("axes").path("speech").path("score").isNull()).isTrue();
        assertThat(result.path("axes").path("gaze").path("status").asText()).isEqualTo("ok");
        assertThat(result.path("overall").path("axes_failed").toString()).isEqualTo("[\"speech\"]");
    }

    @Test
    void 영상_URL_에_fail_이_있으면_시선_축만_실패한_partial() {
        String taskId = client.requestReport("sess_1", "rpt_sess_1_1", request(List.of(
                answer("q_1", "question", "https://s3/video/fail.mp4"),
                answer("q_2", "followup", null))));

        JsonNode result = pollToEnd(taskId).result();

        assertThat(result.path("report_status").asText()).isEqualTo("partial");
        assertThat(result.path("axes").path("gaze").path("status").asText()).isEqualTo("failed");
        assertThat(result.path("axes").path("gaze").path("error_code").asText()).isEqualTo("GAZE_FAILED");
        assertThat(result.path("axes").path("speech").path("status").asText()).isEqualTo("ok");
        assertThat(result.path("overall").path("axes_used").toString()).isEqualTo("[\"content\",\"speech\"]");
    }

    private AiReportTaskStatusResponse pollToEnd(String taskId) {
        AiReportTaskStatusResponse status = client.getReportTask(taskId);
        while (!status.isDone() && !status.isFailed()) {
            status = client.getReportTask(taskId);
        }
        return status;
    }

    private AiReportRequest request(List<AiReportAnswer> answers) {
        return new AiReportRequest("friendly", "백엔드 개발", null, null, answers);
    }

    private AiReportAnswer answer(String questionId, String type, String videoUrl) {
        return answer(questionId, type, "https://s3/a.webm", videoUrl);
    }

    private AiReportAnswer answer(String questionId, String type, String audioUrl, String videoUrl) {
        return new AiReportAnswer(questionId, type, "질문", null, null, 1,
                audioUrl, videoUrl, false, null, false, false);
    }
}
