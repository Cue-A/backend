package com.cuea.infrastructure.ai;

import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재시도·재녹음 판정을 검증합니다.
 *
 * <p>AI 원본 errorCode 문자열은 {@code AiPoller} 가 {@link com.cuea.common.exception.BusinessException}
 * 으로 옮기면서 사라지므로, 그 뒤 흐름(WebSocket error push)에서는 우리 {@link ErrorCode}
 * 로만 판정해야 합니다. 이 테스트는 {@code ErrorCode} 기반 오버로드가 timeout·unexpected
 * 같은 Backend 자체 코드를 재시도 대상으로 잘못 분류하지 않는지 봅니다.
 */
class AiErrorTranslatorTest {

    private final AiErrorTranslator translator = new AiErrorTranslator();

    @Test
    void ErrorCode_기준_재시도는_LLM_STT_만_true_다() {
        assertThat(translator.isRetryable(ErrorCode.LLM_FAILED)).isTrue();
        assertThat(translator.isRetryable(ErrorCode.STT_FAILED)).isTrue();

        // Backend 자체 코드는 AI 원본 코드가 아니므로 재시도 대상이 아니다.
        assertThat(translator.isRetryable(ErrorCode.AI_TIMEOUT)).isFalse();
        assertThat(translator.isRetryable(ErrorCode.AI_UNAVAILABLE)).isFalse();
        assertThat(translator.isRetryable(ErrorCode.UNEXPECTED_AI_RESPONSE)).isFalse();
        assertThat(translator.isRetryable(ErrorCode.TTS_FAILED)).isFalse();
    }

    @Test
    void ErrorCode_기준_재녹음은_STT_만_true_다() {
        assertThat(translator.needsRerecord(ErrorCode.STT_FAILED)).isTrue();
        assertThat(translator.needsRerecord(ErrorCode.LLM_FAILED)).isFalse();
        assertThat(translator.needsRerecord(ErrorCode.AI_TIMEOUT)).isFalse();
        assertThat(translator.needsRerecord(ErrorCode.UNEXPECTED_AI_RESPONSE)).isFalse();
    }

    @Test
    void 문자열_기준_판정은_AI_원본_코드를_따른다() {
        assertThat(translator.isRetryable("LLM_FAILED")).isTrue();
        assertThat(translator.isRetryable("STT_FAILED")).isTrue();
        assertThat(translator.isRetryable("TTS_FAILED")).isFalse();
        assertThat(translator.isRetryable((String) null)).isFalse();
    }
}
