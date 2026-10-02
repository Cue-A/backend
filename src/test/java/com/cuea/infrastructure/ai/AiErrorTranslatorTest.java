package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 표현 → Backend 표현 <b>변환</b>을 검증합니다. (Issue #53)
 *
 * <p>재시도·세션 정리·재녹음·중복 제출 같은 도메인 정책 판정은 이 변환기에서 빠졌고,
 * {@code AnswerFailurePolicy}(답변 흐름)로 옮겼습니다. 여기서는 코드 매핑·예외 생성만
 * 봅니다.
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
