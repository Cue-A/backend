package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 표현 → Backend 표현 변환을 검증합니다. 코드 매핑·예외 생성만 봅니다.
 */
class AiErrorTranslatorTest {

    private final AiErrorTranslator translator = new AiErrorTranslator();

    @Test
    void 알려진_AI_코드는_같은_이름의_ErrorCode_로_옮긴다() {
        assertThat(translator.toErrorCode("LLM_FAILED")).isEqualTo(ErrorCode.LLM_FAILED);
        assertThat(translator.toErrorCode("STT_FAILED")).isEqualTo(ErrorCode.STT_FAILED);
        assertThat(translator.toErrorCode("SESSION_ENDED")).isEqualTo(ErrorCode.SESSION_ENDED);
    }

    @Test
    void 모르는_코드와_null_은_AI_UNAVAILABLE_로_옮긴다() {
        // 계약에 없는 문자열·null 은 상태 불명으로 보고 AI_UNAVAILABLE 로 수렴시킨다.
        assertThat(translator.toErrorCode(null)).isEqualTo(ErrorCode.AI_UNAVAILABLE);
        assertThat(translator.toErrorCode("SOMETHING_NEW")).isEqualTo(ErrorCode.AI_UNAVAILABLE);
    }

    @Test
    void toException_은_코드를_옮기고_메시지가_null_이면_기본_메시지를_쓴다() {
        BusinessException withMessage = translator.toException("LLM_FAILED", "질문 생성 실패");
        assertThat(withMessage.getErrorCode()).isEqualTo(ErrorCode.LLM_FAILED);
        assertThat(withMessage.getMessage()).isEqualTo("질문 생성 실패");

        BusinessException nullMessage = translator.toException("STT_FAILED", null);
        assertThat(nullMessage.getErrorCode()).isEqualTo(ErrorCode.STT_FAILED);
        assertThat(nullMessage.getMessage()).isEqualTo(ErrorCode.STT_FAILED.getMessage());
    }
}
