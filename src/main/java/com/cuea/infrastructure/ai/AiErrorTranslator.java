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
     * TTS 실패는 질문 텍스트가 이미 만들어진 상태일 수 있습니다.
     * 음성 없이 텍스트로 계속 진행해야 하며 프론트에도 알려야 합니다.
     */
    public boolean isAudioOnlyFailure(String aiErrorCode) {
        return "TTS_FAILED".equals(aiErrorCode);
    }

    /** 복구 불가라 세션을 aborted 로 내려야 하는 경우. */
    public boolean requiresSessionAbort(String aiErrorCode) {
        return "SESSION_NOT_FOUND".equals(aiErrorCode);
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
