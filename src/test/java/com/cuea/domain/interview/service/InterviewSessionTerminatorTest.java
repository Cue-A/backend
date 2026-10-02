package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 세션 종료 공용 컴포넌트를 검증합니다. (Issue #53)
 *
 * <p>핵심: AI abort 는 best-effort(실패해도 로컬 정리 보장), 로컬 {@code ABORTED}
 * 정리는 항상 수행, {@code terminateQuietly} 는 정리 실패를 원인 예외에 suppressed 로
 * 붙여 원인을 잃지 않는다.
 */
class InterviewSessionTerminatorTest {

    private static final String SESSION_ID = "sess_1";

    private AiClient aiClient;
    private InterviewSessionWriter sessionWriter;
    private InterviewSessionTerminator terminator;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        sessionWriter = mock(InterviewSessionWriter.class);
        terminator = new InterviewSessionTerminator(aiClient, sessionWriter);
    }

    @Test
    void terminate_는_AI_abort_후_로컬_ABORTED_순서로_정리한다() {
        terminator.terminate(SESSION_ID);

        var order = inOrder(aiClient, sessionWriter);
        order.verify(aiClient).abortSession(SESSION_ID);
        order.verify(sessionWriter).markAborted(SESSION_ID);
    }

    @Test
    void terminate_는_AI_abort_가_실패해도_로컬_정리를_수행한다() {
        // AI 서버가 죽었어도 우리 DB 세션은 ABORTED 로 정리해야 영구 잔류하지 않는다.
        doThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE))
                .when(aiClient).abortSession(SESSION_ID);

        terminator.terminate(SESSION_ID);   // 예외를 밖으로 던지지 않는다.

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
    }

    @Test
    void terminateQuietly_는_원인_예외를_잃지_않고_정리한다() {
        BusinessException cause = new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "원인");

        terminator.terminateQuietly(SESSION_ID, cause);

        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
    }

    @Test
    void terminateQuietly_는_정리_실패를_원인에_suppressed_로_붙이고_삼킨다() {
        // AI abort 와 markAborted 가 모두 실패해도 원인 예외를 밖으로 다시 던지지 않고,
        // 정리 실패는 suppressed 로 붙여 원래 실패 원인을 보존한다.
        BusinessException cause = new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "원인");
        RuntimeException abortError = new BusinessException(ErrorCode.AI_UNAVAILABLE);
        RuntimeException markError = new IllegalStateException("db down");
        doThrow(abortError).when(aiClient).abortSession(SESSION_ID);
        doThrow(markError).when(sessionWriter).markAborted(SESSION_ID);

        terminator.terminateQuietly(SESSION_ID, cause);   // 예외를 밖으로 던지지 않는다.

        assertThat(cause.getSuppressed()).containsExactlyInAnyOrder(abortError, markError);
    }
}
