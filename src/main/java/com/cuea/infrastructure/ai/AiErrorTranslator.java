package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 서버 errorCode 를 우리 {@link ErrorCode}/{@link BusinessException} 으로 옮기는
 * 변환기입니다. AI 표현 → Backend 표현 변환만 담당합니다.
 *
 * <p>재시도·세션 정리·재녹음·중복 제출 같은 도메인 정책은 흐름마다 규칙이 달라(예:
 * {@code STT_FAILED} 는 답변에선 1회 재시도, 첫 질문/리포트에선 재시도 없음) 각 도메인에
 * 둡니다: 답변은 {@code AnswerFailurePolicy}, 리포트는 {@code ReportFailurePolicy}.
 * 표는 docs/10-ai-client.md 참고.
 */
@Slf4j
@Component
public class AiErrorTranslator {

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
