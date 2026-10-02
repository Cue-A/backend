package com.cuea.domain.interview.service;

import com.cuea.common.exception.ErrorCode;

import java.util.Set;

/**
 * 답변 처리 흐름의 AI 실패를 어떻게 다룰지 정하는 면접 도메인 정책.
 *
 * <p><b>답변(Answer) 흐름 전용입니다.</b> 세션 시작(첫 질문) 흐름은 정책이 다릅니다
 * (예: Backend 는 startSession 을 재전송하지 않고, {@code SESSION_ENDED} 를 무시하지 않고
 * 정리함). 두 흐름을 하나의 정책으로 합치지 않습니다. 첫 질문 흐름의 판정은
 * {@link InterviewFirstQuestionPoller} 가 자체적으로 가집니다.
 *
 * <p>판정은 모두 우리 {@link ErrorCode} 기준입니다. AI 원본 문자열은 {@code AiPoller} 가
 * {@code BusinessException} 으로 옮기면서 사라지기 때문입니다.
 */
final class AnswerFailurePolicy {

    /** 답변 처리의 최대 자동 재전송 횟수. 1회 고정입니다. 늘리지 마세요. */
    static final int MAX_RETRY = 1;

    /**
     * 같은 요청을 한 번 더 보내볼 만한 실패. {@code LLM_FAILED}·{@code STT_FAILED} 는
     * 이름이 AI 원본 코드와 1:1 이라 같은 값을 가리킵니다.
     */
    private static final Set<ErrorCode> RETRYABLE = Set.of(
            ErrorCode.LLM_FAILED, ErrorCode.STT_FAILED);

    /**
     * 복구 불가/상태 불명이라 세션을 {@code ABORTED} 로 정리해야 하는 코드. AI·Backend
     * 세션 상태 동기화를 보장할 수 없는 경우들입니다.
     */
    private static final Set<ErrorCode> REQUIRES_ABORT = Set.of(
            ErrorCode.SESSION_NOT_FOUND,
            ErrorCode.RESUME_PARSE_FAILED,
            ErrorCode.AI_TIMEOUT,
            ErrorCode.AI_UNAVAILABLE,
            ErrorCode.UNEXPECTED_AI_RESPONSE);

    /** 클라이언트·재연습 조립 버그 계열. 세션을 유지하고 통지만 합니다. */
    private static final Set<ErrorCode> CLIENT_CONTRACT_ERROR = Set.of(
            ErrorCode.INVALID_QUESTION_ID,
            ErrorCode.INVALID_CATEGORY);

    private AnswerFailurePolicy() {
    }

    static boolean isRetryable(ErrorCode errorCode) {
        return RETRYABLE.contains(errorCode);
    }

    static boolean requiresSessionAbort(ErrorCode errorCode) {
        return REQUIRES_ABORT.contains(errorCode);
    }

    /** {@code SESSION_ENDED} — 이미 종료된 세션에 들어온 중복 제출. 무시합니다. */
    static boolean isDuplicateSubmit(ErrorCode errorCode) {
        return errorCode == ErrorCode.SESSION_ENDED;
    }

    static boolean isClientContractError(ErrorCode errorCode) {
        return CLIENT_CONTRACT_ERROR.contains(errorCode);
    }
}
