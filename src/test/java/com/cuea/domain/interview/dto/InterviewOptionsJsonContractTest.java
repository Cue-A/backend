package com.cuea.domain.interview.dto;

import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.dto.response.InterviewSessionOptionsResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 프론트 방향 JSON 계약을 실제 Jackson 직렬화/역직렬화로 봅니다(Issue #99 리뷰).
 *
 * <p>컨트롤러 단위 테스트는 직접 호출이라 Jackson 을 거치지 않습니다. 프론트가 실제로
 * 주고받는 것은 JSON 이므로, 필드 이름(camelCase)·null 직렬화·타입 역직렬화를 여기서
 * 확인합니다. 프론트 방향 매퍼는 기본 camelCase 입니다({@code JacksonConfig} 는 naming
 * 전략을 바꾸지 않고 JavaTimeModule 만 추가). 그 동작을 그대로 재현합니다.
 */
class InterviewOptionsJsonContractTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void 요청_JSON_의_answerTimeLimitSec_가_Integer_로_역직렬화된다() throws Exception {
        String json = """
                {"documentId":"11111111-1111-1111-1111-111111111111","companyId":null,
                 "jobRole":"백엔드 개발","persona":"PRESSURE","questionCount":6,
                 "hideQuestionText":true,"answerTimeLimitSec":90}
                """;

        InterviewStartRequest req = mapper.readValue(json, InterviewStartRequest.class);

        assertThat(req.answerTimeLimitSec()).isEqualTo(90);
        assertThat(req.hideQuestionText()).isTrue();
        assertThat(req.persona()).isEqualTo(Persona.PRESSURE);
    }

    @Test
    void 요청_JSON_에_answerTimeLimitSec_명시_null_도_필드_누락도_모두_null_로_역직렬화된다() throws Exception {
        String explicitNull = """
                {"documentId":"d","jobRole":"백엔드","persona":"FRIENDLY","answerTimeLimitSec":null}
                """;
        String omitted = """
                {"documentId":"d","jobRole":"백엔드","persona":"FRIENDLY"}
                """;

        // record + Jackson 에서는 두 경우가 구별되지 않는다(둘 다 null). 정책의 전제.
        assertThat(mapper.readValue(explicitNull, InterviewStartRequest.class).answerTimeLimitSec()).isNull();
        assertThat(mapper.readValue(omitted, InterviewStartRequest.class).answerTimeLimitSec()).isNull();
    }

    @Test
    void 조회_응답_JSON_은_camelCase_필드명이고_null_타이머가_명시적_null_로_직렬화된다() throws Exception {
        InterviewSession legacy = session(Persona.FRIENDLY, false, null);
        InterviewSessionOptionsResponse res = InterviewSessionOptionsResponse.from(legacy);

        String json = mapper.writeValueAsString(res);

        // 프론트 계약 필드명(camelCase)과 소문자 스타일.
        assertThat(json).contains("\"hideQuestionText\":false");
        assertThat(json).contains("\"interviewerStyle\":\"friendly\"");
        // 제한 없음은 명시적 null 로 내려가야 한다(키 누락이 아니라 null).
        assertThat(json).contains("\"answerTimeLimitSec\":null");
        // 내부 컬럼명(duration_limit/persona)이 새어나오면 안 된다.
        assertThat(json).doesNotContain("durationLimit");
        assertThat(json).doesNotContain("duration_limit");
        assertThat(json).doesNotContain("persona");
    }

    @Test
    void 조회_응답_JSON_은_타이머_값이_있으면_그대로_직렬화된다() throws Exception {
        InterviewSession s = session(Persona.PRESSURE, true, 120);
        String json = mapper.writeValueAsString(InterviewSessionOptionsResponse.from(s));

        assertThat(json).contains("\"answerTimeLimitSec\":120");
        assertThat(json).contains("\"interviewerStyle\":\"pressure\"");
        assertThat(json).contains("\"hideQuestionText\":true");
    }

    private InterviewSession session(Persona persona, boolean hide, Integer durationLimit) {
        return InterviewSession.builder()
                .sessionId("sess_x").user(mock(User.class))
                .document(mock(com.cuea.domain.document.entity.Document.class))
                .mode("PRACTICE").jobRole("백엔드").questionCount(6)
                .persona(persona).hideQuestionText(hide).durationLimit(durationLimit)
                .status(SessionStatus.IN_PROGRESS).build();
    }
}
