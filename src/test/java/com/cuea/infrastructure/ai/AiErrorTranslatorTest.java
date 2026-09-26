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

    // ── cleanup 정책 분류 (Issue #25) ────────────────────────────

    @Test
    void 세션_정리가_필요한_코드는_복구_불가_계열이다() {
        // 세션을 ABORTED 로 내려야 하는(AI·Backend 상태 동기화 보장 불가) 코드들.
        assertThat(translator.requiresSessionAbort(ErrorCode.SESSION_NOT_FOUND)).isTrue();
        assertThat(translator.requiresSessionAbort(ErrorCode.RESUME_PARSE_FAILED)).isTrue();
        assertThat(translator.requiresSessionAbort(ErrorCode.AI_TIMEOUT)).isTrue();
        assertThat(translator.requiresSessionAbort(ErrorCode.AI_UNAVAILABLE)).isTrue();
        assertThat(translator.requiresSessionAbort(ErrorCode.UNEXPECTED_AI_RESPONSE)).isTrue();
    }

    @Test
    void 재시도_대상과_중복제출_클라이언트버그_는_세션_정리_대상이_아니다() {
        // LLM/STT 는 재시도·재녹음 대상이라 세션을 유지한다.
        assertThat(translator.requiresSessionAbort(ErrorCode.LLM_FAILED)).isFalse();
        assertThat(translator.requiresSessionAbort(ErrorCode.STT_FAILED)).isFalse();
        // TTS 는 텍스트로 진행하므로 실패가 아니다.
        assertThat(translator.requiresSessionAbort(ErrorCode.TTS_FAILED)).isFalse();
        // 중복 제출·클라이언트 버그는 세션을 abort 하지 않는다.
        assertThat(translator.requiresSessionAbort(ErrorCode.SESSION_ENDED)).isFalse();
        assertThat(translator.requiresSessionAbort(ErrorCode.INVALID_QUESTION_ID)).isFalse();
        assertThat(translator.requiresSessionAbort(ErrorCode.INVALID_CATEGORY)).isFalse();
    }

    @Test
    void SESSION_ENDED_는_중복_제출로_무시_대상이다() {
        assertThat(translator.isDuplicateSubmit(ErrorCode.SESSION_ENDED)).isTrue();
        assertThat(translator.isDuplicateSubmit(ErrorCode.SESSION_NOT_FOUND)).isFalse();
        assertThat(translator.isDuplicateSubmit(ErrorCode.STT_FAILED)).isFalse();
    }

    @Test
    void INVALID_QUESTION_ID_와_INVALID_CATEGORY_는_클라이언트_계약_오류다() {
        assertThat(translator.isClientContractError(ErrorCode.INVALID_QUESTION_ID)).isTrue();
        assertThat(translator.isClientContractError(ErrorCode.INVALID_CATEGORY)).isTrue();
        assertThat(translator.isClientContractError(ErrorCode.STT_FAILED)).isFalse();
        assertThat(translator.isClientContractError(ErrorCode.SESSION_NOT_FOUND)).isFalse();
    }
}
