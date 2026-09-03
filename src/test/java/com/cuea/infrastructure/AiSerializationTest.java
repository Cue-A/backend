package com.cuea.infrastructure;

import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiSessionStartRequest;
import com.cuea.infrastructure.websocket.message.ProgressStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 경계 변환")
class AiSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void AI_방향_JSON_은_snake_case_다() throws Exception {
        String json = objectMapper.writeValueAsString(new AiSessionStartRequest(
                "https://example.com/resume.pdf", "백엔드 개발", "pressure",
                "hyundai_enc", 6, "sess_1st", List.of()));

        assertThat(json).contains("\"resume_file_url\"", "\"job_role\"",
                "\"question_count\"", "\"retry_of_session_id\"", "\"replay_log\"");
        assertThat(json).doesNotContain("resumeFileUrl");
    }

    @Test
    void 두_플래그는_항상_채워져서_온다() throws Exception {
        String payload = """
                {"type":"question","question_id":"q_4","text":"왜 낙관적 락을 선택하셨나요?",
                 "audio_url":null,"category":"프로젝트경험","difficulty":"L2",
                 "question_number":4,"question_total":9,"topic_index":2,"topic_total":4,
                 "is_spare_topic":false,"is_replay":false}
                """;

        AiQuestionResult result = objectMapper.readValue(payload, AiQuestionResult.class);

        assertThat(result.category()).isEqualTo("프로젝트경험");
        assertThat(result.isSpareTopic()).isFalse();
        assertThat(result.isReplay()).isFalse();
        // TTS 실패 시 audio_url 만 null 이고 나머지는 채워집니다.
        assertThat(result.audioUrl()).isNull();
        assertThat(result.text()).isNotBlank();
    }

    @Test
    void AI_의_stage_는_우리_enum_으로_바꿔서_내보낸다() {
        assertThat(ProgressStage.from("stt")).isEqualTo(ProgressStage.TRANSCRIBING);
        assertThat(ProgressStage.from("generating")).isEqualTo(ProgressStage.GENERATING);
        assertThat(ProgressStage.from("tts")).isEqualTo(ProgressStage.SYNTHESIZING);
        // AI 가 새 단계를 추가해도 프론트가 깨지지 않게 기본값으로 떨어집니다.
        assertThat(ProgressStage.from("something-new")).isEqualTo(ProgressStage.GENERATING);
    }
}
