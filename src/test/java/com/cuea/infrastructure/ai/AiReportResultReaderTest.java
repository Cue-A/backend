package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 저장 전 검증. 역직렬화는 키가 빠져도 null 로 통과하므로 필수 필드를 따로 봅니다.
 */
class AiReportResultReaderTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final AiReportResultReader reader = new AiReportResultReader(objectMapper);

    @Test
    void 필수_필드가_있으면_통과한다() throws Exception {
        assertThatCode(() -> reader.validate(valid())).doesNotThrowAnyException();
    }

    /** 실패·skipped 축은 점수가 null 이고, 글로 된 칸은 생성에 실패하면 비어 옵니다. */
    @Test
    void 실패_축의_점수나_글로_된_칸이_비어도_통과한다() throws Exception {
        ObjectNode result = valid();
        ((ObjectNode) result.at("/axes/gaze")).put("status", "skipped").putNull("score");
        result.putNull("resilience");
        result.putNull("company_comment");
        result.remove("improved_answers");

        assertThatCode(() -> reader.validate(result)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/overall", "/overall/score", "/axes", "/axes/content", "/axes/speech",
            "/axes/gaze/status", "/questions"})
    void 필수_필드가_빠지면_UNEXPECTED_AI_RESPONSE(String pointer) throws Exception {
        ObjectNode result = valid();
        remove(result, pointer);

        assertThatThrownBy(() -> reader.validate(result))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNEXPECTED_AI_RESPONSE);
    }

    @Test
    void 타입이_어긋나면_UNEXPECTED_AI_RESPONSE() throws Exception {
        ObjectNode result = valid();
        result.put("questions", "배열이어야 하는 자리");

        assertThatThrownBy(() -> reader.validate(result))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNEXPECTED_AI_RESPONSE);
    }

    private ObjectNode valid() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {"report_status":"complete",
                 "overall":{"score":68,"display":4,"gated":false,"partial":false},
                 "axes":{"content":{"status":"ok","score":72},
                         "speech":{"status":"ok","score":61},
                         "gaze":{"status":"ok","score":65}},
                 "questions":[],
                 "improved_answers":[]}
                """);
    }

    private static void remove(ObjectNode root, String pointer) {
        int slash = pointer.lastIndexOf('/');
        JsonNode parent = slash == 0 ? root : root.at(pointer.substring(0, slash));
        ((ObjectNode) parent).remove(pointer.substring(slash + 1));
    }
}
