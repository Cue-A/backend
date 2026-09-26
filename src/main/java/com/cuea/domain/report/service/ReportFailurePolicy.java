package com.cuea.domain.report.service;

import com.cuea.common.exception.ErrorCode;

import java.util.Set;

/**
 * 리포트 실패를 어떻게 다룰지. 리포트 계약 9장의 재시도 분류를 따릅니다.
 *
 * <p>{@code AiErrorTranslator} 에 넣지 않은 이유: 같은 코드라도 질문 생성과 리포트의
 * 재시도 규칙이 다릅니다(예: {@code STT_FAILED} 는 질문에선 1회 재시도, 리포트에선
 * 재시도 없음). 리포트 규칙은 리포트 도메인에 둡니다.
 */
final class ReportFailurePolicy {

    /** 백그라운드에서 새 Idempotency-Key 로 한 번 더 요청합니다. */
    private static final Set<ErrorCode> AUTO_RETRY = Set.of(
            ErrorCode.CONTENT_FAILED,       // LLM 일시 오류 가능성
            ErrorCode.MEDIA_FETCH_FAILED);  // presigned URL 만료 가능성. 재조립하면 새 URL

    /** 사용자가 등록 API 로 다시 요청해 볼 만한 실패. */
    private static final Set<ErrorCode> USER_RETRYABLE = Set.of(
            ErrorCode.CONTENT_FAILED,
            ErrorCode.MEDIA_FETCH_FAILED,
            ErrorCode.AI_TIMEOUT,
            ErrorCode.AI_UNAVAILABLE);

    private ReportFailurePolicy() {
    }

    static boolean shouldAutoRetry(ErrorCode errorCode) {
        return AUTO_RETRY.contains(errorCode);
    }

    /**
     * WebSocket {@code error.retryable}. {@code STT_FAILED} 는 오디오 자체 문제일 가능성이
     * 높아 다시 돌려도 같습니다.
     */
    static boolean isUserRetryable(ErrorCode errorCode) {
        return USER_RETRYABLE.contains(errorCode);
    }
}
