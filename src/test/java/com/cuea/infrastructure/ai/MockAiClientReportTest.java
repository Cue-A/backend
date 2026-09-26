package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.dto.AiReportAnswer;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 로컬 기본값이 mock 이라, mock 이 실제 AI 계약과 다르게 굴면 리포트 흐름을 로컬에서
 * 검증할 수 없습니다. 계약의 세 가지(2문항 미만 422, 멱등 키, processing 후 done)를 봅니다.
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
    void 영상이_있으면_시선_축도_점수가_나온다() {
        String taskId = client.requestReport("sess_1", "rpt_sess_1_1",
                request(List.of(answer("q_1", "question", "https://s3/v1.mp4"), answer("q_2", "followup", null))));

        AiReportTaskStatusResponse status = client.getReportTask(taskId);
        while (!status.isDone()) {
            status = client.getReportTask(taskId);
        }
        assertThat(status.result().path("axes").path("gaze").path("status").asText()).isEqualTo("ok");
    }

    private AiReportRequest request(List<AiReportAnswer> answers) {
        return new AiReportRequest("friendly", "백엔드 개발", null, null, answers);
    }

    private AiReportAnswer answer(String questionId, String type, String videoUrl) {
        return new AiReportAnswer(questionId, type, "질문", null, null, 1,
                "https://s3/a.webm", videoUrl, false, null, false, false);
    }
}
