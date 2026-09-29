package com.cuea.infrastructure.ai;

import com.cuea.infrastructure.ai.dto.AiAnswerSubmitRequest;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.cuea.infrastructure.ai.dto.AiSessionStartRequest;
import com.cuea.infrastructure.ai.dto.AiSessionStartResponse;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;

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

    /**
     * 세션 중단. AI 쪽 상태도 정리합니다.
     * 타임아웃·사용자 이탈 시 반드시 호출하세요. 안 하면 세션이 영원히 남습니다.
     */
    void abortSession(String sessionId);

    /**
     * 리포트 생성 요청. task_id 를 돌려받습니다.
     *
     * <p>{@code idempotencyKey} 가 같으면 AI 는 새 작업을 만들지 않고 기존 task_id 를
     * 돌려줍니다. 실패한 작업을 다시 돌리려면 <b>시도 번호를 올린 새 키</b>를 쓰세요.
     * 같은 키로 보내면 실패한 task_id 가 그대로 돌아옵니다.
     *
     * @throws com.cuea.common.exception.BusinessException 답변이 2문항 미만이면 {@code REPORT_TOO_SHORT}
     */
    String requestReport(String sessionId, String idempotencyKey, AiReportRequest request);

    /** 리포트 작업 상태 조회. 경로는 {@link #getTask} 와 같고 결과 모양만 다릅니다. */
    AiReportTaskStatusResponse getReportTask(String taskId);
}
