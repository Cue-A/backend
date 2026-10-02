package com.cuea.infrastructure.websocket.message;

import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최종 오류 push 메시지 조립 규칙을 검증합니다.
 *
 * <ul>
 *   <li>항상 최종 지점이라 {@code retryable=false}.</li>
 *   <li>{@code STT_FAILED} 만 {@code needsRerecord=true}(같은 오디오로는 결과가 같음).</li>
 * </ul>
 */
class ErrorPushMessageTest {

    @Test
    void 최종오류는_항상_retryable_false_다() {
        // 재시도 가능 성격의 코드라도 error push 시점엔 자동 재시도가 끝났다.
        assertThat(payloadOf(ErrorCode.LLM_FAILED).retryable()).isFalse();
        assertThat(payloadOf(ErrorCode.AI_UNAVAILABLE).retryable()).isFalse();
        assertThat(payloadOf(ErrorCode.STT_FAILED).retryable()).isFalse();
    }

    @Test
    void STT_FAILED_만_needsRerecord_true_다() {
        assertThat(payloadOf(ErrorCode.STT_FAILED).needsRerecord()).isTrue();
        assertThat(payloadOf(ErrorCode.LLM_FAILED).needsRerecord()).isFalse();
        assertThat(payloadOf(ErrorCode.AI_TIMEOUT).needsRerecord()).isFalse();
        assertThat(payloadOf(ErrorCode.UNEXPECTED_AI_RESPONSE).needsRerecord()).isFalse();
    }

    @Test
    void errorCode_이름과_메시지를_그대로_담는다() {
        ErrorPushMessage payload = payloadOf(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다");
        assertThat(payload.errorCode()).isEqualTo("STT_FAILED");
        assertThat(payload.message()).isEqualTo("음성을 인식하지 못했습니다");
    }

    @Test
    void type_은_error_다() {
        assertThat(ErrorPushMessage.finalFailure(ErrorCode.LLM_FAILED, "x").type()).isEqualTo("error");
    }

    private ErrorPushMessage payloadOf(ErrorCode code) {
        return payloadOf(code, "msg");
    }

    private ErrorPushMessage payloadOf(ErrorCode code, String message) {
        return (ErrorPushMessage) ErrorPushMessage.finalFailure(code, message).payload();
    }
}
