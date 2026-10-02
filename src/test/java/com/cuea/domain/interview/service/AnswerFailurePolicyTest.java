package com.cuea.domain.interview.service;

import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 답변 처리 흐름의 AI 실패 정책을 검증합니다. (Issue #53)
 *
 * <p>이 판정들은 {@code AiErrorTranslator} 에서 분리돼 이 도메인 정책으로 옮겨졌습니다.
 * 판정은 모두 우리 {@link ErrorCode} 기준입니다(AI 원본 문자열은 {@code AiPoller} 가
 * {@code BusinessException} 으로 옮기며 사라짐). timeout·unexpected 같은 Backend 자체
 * 코드를 재시도 대상으로 잘못 분류하지 않는지도 함께 봅니다.
 */
class AnswerFailurePolicyTest {

    @Test
    void 재전송은_LLM_STT_만_대상이다() {
        assertThat(AnswerFailurePolicy.isRetryable(ErrorCode.LLM_FAILED)).isTrue();
        assertThat(AnswerFailurePolicy.isRetryable(ErrorCode.STT_FAILED)).isTrue();

        // Backend 자체 코드·TTS 는 재전송 대상이 아니다.
        assertThat(AnswerFailurePolicy.isRetryable(ErrorCode.AI_TIMEOUT)).isFalse();
        assertThat(AnswerFailurePolicy.isRetryable(ErrorCode.AI_UNAVAILABLE)).isFalse();
        assertThat(AnswerFailurePolicy.isRetryable(ErrorCode.UNEXPECTED_AI_RESPONSE)).isFalse();
        assertThat(AnswerFailurePolicy.isRetryable(ErrorCode.TTS_FAILED)).isFalse();
    }

    @Test
    void 최대_재전송은_1회다() {
        // #25 확정 정책. 늘리지 않는다.
        assertThat(AnswerFailurePolicy.MAX_RETRY).isEqualTo(1);
    }

    @Test
    void 세션_정리가_필요한_코드는_복구_불가_계열이다() {
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.SESSION_NOT_FOUND)).isTrue();
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.RESUME_PARSE_FAILED)).isTrue();
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.AI_TIMEOUT)).isTrue();
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.AI_UNAVAILABLE)).isTrue();
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.UNEXPECTED_AI_RESPONSE)).isTrue();
    }

    @Test
    void 재시도_대상과_중복제출_클라이언트버그_는_세션_정리_대상이_아니다() {
        // LLM/STT 는 재시도·재녹음 대상이라 세션을 유지한다.
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.LLM_FAILED)).isFalse();
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.STT_FAILED)).isFalse();
        // TTS 는 텍스트로 진행하므로 실패가 아니다.
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.TTS_FAILED)).isFalse();
        // 중복 제출·클라이언트 버그는 세션을 abort 하지 않는다.
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.SESSION_ENDED)).isFalse();
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.INVALID_QUESTION_ID)).isFalse();
        assertThat(AnswerFailurePolicy.requiresSessionAbort(ErrorCode.INVALID_CATEGORY)).isFalse();
    }

    @Test
    void SESSION_ENDED_는_중복_제출로_무시_대상이다() {
        assertThat(AnswerFailurePolicy.isDuplicateSubmit(ErrorCode.SESSION_ENDED)).isTrue();
        assertThat(AnswerFailurePolicy.isDuplicateSubmit(ErrorCode.SESSION_NOT_FOUND)).isFalse();
        assertThat(AnswerFailurePolicy.isDuplicateSubmit(ErrorCode.STT_FAILED)).isFalse();
    }

    @Test
    void INVALID_QUESTION_ID_와_INVALID_CATEGORY_는_클라이언트_계약_오류다() {
        assertThat(AnswerFailurePolicy.isClientContractError(ErrorCode.INVALID_QUESTION_ID)).isTrue();
        assertThat(AnswerFailurePolicy.isClientContractError(ErrorCode.INVALID_CATEGORY)).isTrue();
        assertThat(AnswerFailurePolicy.isClientContractError(ErrorCode.STT_FAILED)).isFalse();
        assertThat(AnswerFailurePolicy.isClientContractError(ErrorCode.SESSION_NOT_FOUND)).isFalse();
    }
}
