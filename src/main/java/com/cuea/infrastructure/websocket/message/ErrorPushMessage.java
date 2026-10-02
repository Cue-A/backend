package com.cuea.infrastructure.websocket.message;

import com.cuea.common.exception.ErrorCode;

/**
 * 오류. type = "error"
 *
 * <p><b>이 push 는 항상 최종 오류입니다.</b> Backend 의 자동 재시도(답변 처리
 * STT/LLM 1회, {@code AnswerFailurePolicy.MAX_RETRY})는 error push 이전에 이미
 * 소진되거나(세션 시작 LLM 처럼) 시도되지 않습니다. 따라서 이 시점에 Backend 가
 * 추가로 자동 재시도하지 않습니다.
 *
 * @param retryable     Backend 가 <b>이 오류에 대해 자동 재시도를 더 할지</b>. error push
 *                      는 재시도가 끝난 최종 지점에서만 나가므로 항상 {@code false} 입니다.
 *                      (내부 재시도 대상 판정은 {@code AnswerFailurePolicy.isRetryable} 가 하며,
 *                      그 결과가 프론트로 그대로 나가지 않습니다.)
 * @param needsRerecord STT 실패처럼 같은 오디오로는 결과가 같아 <b>사용자 재녹음</b>이
 *                      필요한 경우. 최종 {@code STT_FAILED} 에서 {@code true} 입니다.
 */
public record ErrorPushMessage(
        String errorCode,
        String message,
        boolean retryable,
        boolean needsRerecord
) {

    public static SocketMessage<ErrorPushMessage> of(ErrorPushMessage payload) {
        return SocketMessage.of("error", payload);
    }

    /**
     * 최종 오류 push 메시지를 만듭니다. 세션 시작·답변 폴링 양쪽이 동일한 규칙으로
     * 최종 오류를 내려주도록 조립 책임을 여기 모읍니다(error push 자체의 계약). (Issue #53)
     *
     * <p>이 메시지는 <b>항상 최종 지점</b>이므로 {@code retryable=false} 로 고정합니다
     * (Backend 자동 재시도는 이미 끝났거나 대상이 아님). 사용자 다음 행동이 필요한지는
     * {@code needsRerecord} 로만 알리며, {@code STT_FAILED}(같은 오디오로는 결과가 같아
     * 재녹음 필요)일 때 {@code true} 입니다. 이 규칙은 특정 흐름(답변/세션 시작)의 정책이
     * 아니라 <b>error push 메시지 자체의 성질</b>이라 여기 둡니다.
     */
    public static SocketMessage<ErrorPushMessage> finalFailure(ErrorCode errorCode, String message) {
        return of(new ErrorPushMessage(
                errorCode.name(),
                message,
                false,
                errorCode == ErrorCode.STT_FAILED
        ));
    }
}
