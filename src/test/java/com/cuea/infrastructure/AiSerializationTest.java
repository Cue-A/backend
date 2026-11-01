package com.cuea.infrastructure;

import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiReplayLogItem;
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
                "17", "현대건설(주) (종합건설 · 플랜트)\n핵심 가치\n  도전 — 새로운 시도를 두려워하지 않는다",
                6, "sess_1st", List.of(), null));

        assertThat(json).contains("\"resume_file_url\"", "\"job_role\"",
                "\"question_count\"", "\"retry_of_session_id\"", "\"replay_log\"",
                "\"company_profile_override\"");
        // company_id 는 Backend company PK 의 문자열 표현이라 값이 있으면 그대로 실려 나갑니다.
        assertThat(json).contains("\"company_id\":\"17\"");
        // doc_id 는 예약 필드라 항상 null 이며, NON_NULL 정책으로 직렬화에서 빠집니다.
        assertThat(json).doesNotContain("resumeFileUrl", "\"doc_id\"");
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

    @Test
    void 재연습_요청의_replay_log_는_타입별_필드만_snake_case_로_실린다() throws Exception {
        AiSessionStartRequest request = new AiSessionStartRequest(
                "https://example.com/resume.pdf", "백엔드 개발", "pressure",
                null, null, 6, "sess_1st",
                List.of(
                        AiReplayLogItem.question("지원 동기를 말씀해 주세요.", "지원동기", "L1", false),
                        AiReplayLogItem.followup("L2")),
                null);

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"retry_of_session_id\":\"sess_1st\"");
        // 주질문: text/category/difficulty/is_spare_topic 이 snake_case 로 실린다.
        assertThat(json).contains("\"type\":\"question\"", "\"text\":\"지원 동기를 말씀해 주세요.\"",
                "\"category\":\"지원동기\"", "\"difficulty\":\"L1\"", "\"is_spare_topic\":false");
        // 꼬리질문: difficulty 만. NON_NULL 이라 text/category/is_spare_topic 키는 빠진다.
        assertThat(json).contains("\"type\":\"followup\"");
    }

    @Test
    void 옵션_변경으로_일반_면접이_되면_retry_of_session_id_와_replay_log_키가_모두_빠진다() throws Exception {
        // 하나만 보내면 안 되는 규칙: 둘 다 null 이면 NON_NULL 로 두 키가 함께 빠진다.
        AiSessionStartRequest request = new AiSessionStartRequest(
                "https://example.com/resume.pdf", "백엔드 개발", "pressure",
                null, null, 9, null, null, null);

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).doesNotContain("retry_of_session_id");
        assertThat(json).doesNotContain("replay_log");
    }
}
