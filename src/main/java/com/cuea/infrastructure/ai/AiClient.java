package com.cuea.infrastructure.ai;

import com.cuea.infrastructure.ai.dto.AiAnswerSubmitRequest;
import com.cuea.infrastructure.ai.dto.AiCompany;
import com.cuea.infrastructure.ai.dto.AiSessionStartRequest;
import com.cuea.infrastructure.ai.dto.AiSessionStartResponse;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;

import java.util.List;

/**
 * AI 서버와 통신하는 유일한 통로입니다.
 * 도메인 서비스에서 RestClient 를 직접 쓰지 마세요.
 *
 * <p>여기는 전송만 합니다. 폴링은 {@link AiPoller}, 에러 분류는
 * {@link AiErrorTranslator} 가 맡습니다.
 */
public interface AiClient {

    /** 세션 시작. session_id 와 task_id 를 즉시 돌려받습니다. */
    AiSessionStartResponse startSession(AiSessionStartRequest request);

    /** 답변 제출. task_id 를 돌려받습니다. */
    String submitAnswer(String sessionId, AiAnswerSubmitRequest request);

    /** 작업 상태 조회. 폴링용. */
    AiTaskStatusResponse getTask(String taskId);

    /** 회사 목록. DB 에 저장하지 않고 Redis 에 1시간 캐시합니다. */
    List<AiCompany> listCompanies();

    /**
     * 세션 중단. AI 쪽 상태도 정리합니다.
     * 타임아웃·사용자 이탈 시 반드시 호출하세요. 안 하면 세션이 영원히 남습니다.
     */
    void abortSession(String sessionId);
}
