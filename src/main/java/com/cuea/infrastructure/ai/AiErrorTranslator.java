package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * AI 서버 errorCode 를 우리 ErrorCode 로 옮기고 재시도 여부를 판단합니다.
 * 표는 docs/10-ai-client.md 참고.
 *
 * <table>
 *   <tr><td>LLM_FAILED</td><td>1회 재시도</td></tr>
 *   <tr><td>STT_FAILED</td><td>1회 재시도 후 재녹음 안내</td></tr>
 *   <tr><td>TTS_FAILED</td><td>재시도 없음. audio_url=null 로 텍스트만 진행</td></tr>
 *   <tr><td>나머지</td><td>재시도 없음</td></tr>
 * </table>
 */
@Slf4j
@Component
public class AiErrorTranslator {

    /** 같은 요청을 한 번 더 보내볼 만한 것들. */
    private static final Set<String> RETRYABLE = Set.of("LLM_FAILED", "STT_FAILED");

    /** 최대 재시도 횟수. 1회입니다. 늘리지 마세요. */
    public static final int MAX_RETRY = 1;

    public boolean isRetryable(String aiErrorCode) {
        return aiErrorCode != null && RETRYABLE.contains(aiErrorCode);
    }

    /**
     * 우리 {@link ErrorCode} 기준 재시도 가능 여부.
     *
     * <p>AI 원본 errorCode 는 {@code AiPoller} 가 {@link BusinessException} 으로 옮기면서
     * 문자열을 잃습니다. 그 뒤 흐름(예: WebSocket error push)에서는 AI 문자열이 아니라
     * 우리 {@code ErrorCode} 만 남으므로, enum 이름이 아니라 enum 자체로 판정합니다.
     * {@code LLM_FAILED}·{@code STT_FAILED} 는 이름이 AI 코드와 1:1 이라 같은 값을 가리킵니다.
     */
    public boolean isRetryable(ErrorCode errorCode) {
        return errorCode == ErrorCode.LLM_FAILED || errorCode == ErrorCode.STT_FAILED;
    }

    /**
     * TTS 실패는 질문 텍스트가 이미 만들어진 상태일 수 있습니다.
     * 음성 없이 텍스트로 계속 진행해야 하며 프론트에도 알려야 합니다.
     */
    public boolean isAudioOnlyFailure(String aiErrorCode) {
        return "TTS_FAILED".equals(aiErrorCode);
    }

    /** {@link ErrorCode} 기준. STT 실패는 같은 오디오로는 결과가 같아 재녹음 안내가 필요합니다. */
    public boolean needsRerecord(ErrorCode errorCode) {
        return errorCode == ErrorCode.STT_FAILED;
    }

    /** 복구 불가라 세션을 aborted 로 내려야 하는 경우. */
    public boolean requiresSessionAbort(String aiErrorCode) {
        return "SESSION_NOT_FOUND".equals(aiErrorCode);
    }

    /**
     * {@link ErrorCode} 기준 세션 정리 필요 여부. (Issue #25)
     *
     * <p>AI 원본 문자열이 사라진 폴링 흐름에서 쓰는 오버로드입니다. AI·Backend 세션
     * 상태 동기화를 보장할 수 없어 세션을 {@code ABORTED} 로 정리해야 하는 코드들:
     * <ul>
     *   <li>{@code SESSION_NOT_FOUND} — AI 쪽 세션이 사라짐(재배포 등). 복구 불가.</li>
     *   <li>{@code RESUME_PARSE_FAILED} — 이력서를 읽지 못해 진행 불가.</li>
     *   <li>{@code AI_TIMEOUT} / {@code AI_UNAVAILABLE} — 응답을 못 받아 상태 불명.</li>
     *   <li>{@code UNEXPECTED_AI_RESPONSE} — 계약 위반 응답. 상태 동기화 보장 불가.</li>
     * </ul>
     *
     * <p>재시도 대상({@code LLM_FAILED}·{@code STT_FAILED})·TTS·중복 제출
     * ({@code SESSION_ENDED})·클라이언트 계약 오류({@code INVALID_QUESTION_ID}·
     * {@code INVALID_CATEGORY})는 세션을 유지합니다.
     */
    public boolean requiresSessionAbort(ErrorCode errorCode) {
        return errorCode == ErrorCode.SESSION_NOT_FOUND
                || errorCode == ErrorCode.RESUME_PARSE_FAILED
                || errorCode == ErrorCode.AI_TIMEOUT
                || errorCode == ErrorCode.AI_UNAVAILABLE
                || errorCode == ErrorCode.UNEXPECTED_AI_RESPONSE;
    }

    /**
     * 중복 제출로 간주해 <b>무시</b>할 코드. (Issue #25)
     *
     * <p>{@code SESSION_ENDED} 는 AI 가 이미 종료된 세션에 답변이 또 들어온 상황입니다.
     * 계약상 중복 제출이므로 세션을 abort 하거나 error 를 push 하지 않고 로그만 남깁니다.
     */
    public boolean isDuplicateSubmit(ErrorCode errorCode) {
        return errorCode == ErrorCode.SESSION_ENDED;
    }

    /**
     * 클라이언트·조립 버그 계열. (Issue #25)
     *
     * <p>{@code INVALID_QUESTION_ID}(클라이언트 버그)·{@code INVALID_CATEGORY}(재연습
     * 조립 버그)는 세션을 abort 하지 않고, 경고 로그를 남긴 뒤 오류만 전달합니다.
     */
    public boolean isClientContractError(ErrorCode errorCode) {
        return errorCode == ErrorCode.INVALID_QUESTION_ID
                || errorCode == ErrorCode.INVALID_CATEGORY;
    }

    public ErrorCode toErrorCode(String aiErrorCode) {
        if (aiErrorCode == null) {
            return ErrorCode.AI_UNAVAILABLE;
        }
        try {
            return ErrorCode.valueOf(aiErrorCode);
        } catch (IllegalArgumentException e) {
            log.warn("모르는 AI 에러코드 errorCode={}", aiErrorCode);
            return ErrorCode.AI_UNAVAILABLE;
        }
    }

    public BusinessException toException(String aiErrorCode, String message) {
        ErrorCode code = toErrorCode(aiErrorCode);
        return new BusinessException(code, message != null ? message : code.getMessage());
    }
}
