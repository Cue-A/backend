package com.cuea.domain.interview.service;

import com.cuea.infrastructure.ai.AiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 면접 세션 종료(정리) 공용 컴포넌트.
 *
 * <p>"AI 세션 abort(best-effort) → 우리 세션 {@code ABORTED} 정리" 를 수행합니다.
 *
 * <ul>
 *   <li><b>AI abort 는 best-effort</b> — 실패해도(예: AI 서버 다운) 예외를 밖으로
 *       던지지 않고 로컬 정리를 반드시 이어서 수행합니다. 세션이 {@code IN_PROGRESS}
 *       로 영구 잔류하지 않게 하기 위함입니다.</li>
 *   <li><b>로컬 {@code ABORTED} 정리 보장</b> — {@link InterviewSessionWriter#markAborted}
 *       는 세션이 이미 종료됐으면 no-op 이라, {@code COMPLETED}/{@code ABORTED} 역전이
 *       없고 반복 호출도 멱등합니다.</li>
 * </ul>
 *
 * <p>세션 종료만 책임지며 사용자 통지(WebSocket error push)는 하지 않습니다. 통지는
 * 각 호출부가 담당합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewSessionTerminator {

    private final AiClient aiClient;
    private final InterviewSessionWriter sessionWriter;

    /**
     * 세션을 종료합니다. 덮어써야 할 원인 예외가 없는 호출부(사용자 중단·REST 동기
     * 실패)가 씁니다.
     */
    public void terminate(String sessionId) {
        try {
            aiClient.abortSession(sessionId);
        } catch (RuntimeException e) {
            log.warn("AI 세션 중단 실패 sessionId={}. 로컬 정리는 계속합니다.", sessionId, e);
        }
        sessionWriter.markAborted(sessionId);
    }

    /**
     * 세션을 종료하되, 정리 중 발생한 예외를 원인 예외({@code cause})에 suppressed 로
     * 붙여 원인을 잃지 않습니다. 이미 원인 예외가 있는 호출부(백그라운드 폴링 실패 등)가
     * 씁니다.
     */
    public void terminateQuietly(String sessionId, RuntimeException cause) {
        try {
            aiClient.abortSession(sessionId);
        } catch (RuntimeException cleanupError) {
            cause.addSuppressed(cleanupError);
            log.warn("AI 세션 중단 실패 sessionId={}", sessionId, cleanupError);
        }
        try {
            sessionWriter.markAborted(sessionId);
        } catch (RuntimeException cleanupError) {
            cause.addSuppressed(cleanupError);
            log.warn("세션 ABORTED 처리 실패 sessionId={}", sessionId, cleanupError);
        }
    }
}
