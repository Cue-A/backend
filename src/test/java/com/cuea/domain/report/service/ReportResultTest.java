package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.report.entity.ReportStatus;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 리포트 계약 4·6장 예시 모양에서 점수를 꺼냅니다. 키 해석은 AiReportSummary 가 합니다. */
class ReportResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void partial_은_PARTIAL_이고_실패한_축_점수는_null() throws Exception {
        ReportResult result = ReportResult.from(done("""
                {"report_status":"partial","overall":{"score":68},
                 "axes":{"content":{"status":"ok","score":72},
                         "speech":{"status":"ok","score":61},
                         "gaze":{"status":"failed","error_code":"GAZE_FAILED","score":null}}}
                """));

        assertThat(result.status()).isEqualTo(ReportStatus.PARTIAL);
        assertThat(result.scoreTotal()).isEqualTo(68);
        assertThat(result.scoreContent()).isEqualTo(72);
        assertThat(result.scoreSpeech()).isEqualTo(61);
        assertThat(result.scoreGaze()).isNull();
    }

    /** 카메라 미사용은 실패가 아니라 complete 입니다. 시선 점수만 없습니다. */
    @Test
    void 시선이_skipped_여도_complete_면_COMPLETED() throws Exception {
        ReportResult result = ReportResult.from(done("""
                {"report_status":"complete","overall":{"score":70},
                 "axes":{"content":{"score":72},"speech":{"score":61},
                         "gaze":{"status":"skipped","reason":"no_video"}}}
                """));

        assertThat(result.status()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(result.scoreGaze()).isNull();
    }

    @Test
    void 모르는_report_status_면_UNEXPECTED_AI_RESPONSE() throws Exception {
        assertThatThrownBy(() -> ReportResult.from(done("{\"report_status\":\"weird\"}")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNEXPECTED_AI_RESPONSE);
    }

    private AiReportTaskStatusResponse done(String raw) throws Exception {
        JsonNode result = objectMapper.readTree(raw);
        return new AiReportTaskStatusResponse("done", null, null, result, null, null);
    }
}
