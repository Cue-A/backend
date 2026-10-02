package com.cuea.domain.interview.service;

import com.cuea.infrastructure.ai.AiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 면접 세션 종료(정리) 공용 컴포넌트. (Issue #53)
 *
 * <p>여러 흐름에 흩어져 있던 <b>"AI 세션 abort(best-effort) → 우리 세션
 * {@code ABORTED} 정리"</b> 패턴을 한곳에 모읍니다. 이전에는 같은 코드가
 * {@link InterviewStartService}·{@link InterviewAnswerService}·
 * {@link InterviewFirstQuestionPoller}·{@link InterviewAnswerPoller} 네 곳에
 * 중복돼 있었습니다.
 *
 * <h2>정책(동작 무변경)</h2>
 * <ul>
 *   <li><b>AI abort 는 best-effort</b> — 실패해도(예: AI 서버 다운) 예외를 밖으로
 *       던지지 않고, 로컬 정리는 반드시 이어서 수행합니다. AI 서버 장애 때 우리 세션이
 *       {@code IN_PROGRESS} 로 영구 잔류하지 않게 하기 위함입니다.</li>
 *   <li><b>로컬 {@code ABORTED} 정리 보장</b> — {@link InterviewSessionWriter#markAborted}
 *       는 세션이 이미 종료됐으면 no-op 이므로(상태 가드), {@code COMPLETED}/{@code ABORTED}
 *       역전은 일어나지 않고 반복 호출도 멱등합니다.</li>
 * </ul>
 *
 * <h2>책임 경계 — WebSocket 을 알지 못합니다</h2>
 * <p>이 컴포넌트는 <b>세션 종료</b>만 책임집니다. 사용자 통지(WebSocket error push)는
 * 각 흐름(주로 Poller)이 별도로 담당합니다. "세션 정리"와 "사용자 통지"를 분리해,
 * 세션 종료 컴포넌트가 WebSocket·메시지 포맷에 결합되지 않게 했습니다. 따라서
 * {@link SessionSocketHandler} 등 통지 계층에 대한 의존이 여기 생기지 않습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewSessionTerminator {

    private final AiClient aiClient;
    private final InterviewSessionWriter sessionWriter;

    /**
     * 세션을 종료합니다. AI 세션 abort 를 best-effort 로 시도한 뒤 우리 세션을
     * {@code ABORTED} 로 정리합니다.
     *
     * <p>사용자 중단·REST 동기 실패 경로처럼 <b>덮어써야 할 원인 예외가 없는</b>
     * 호출부가 씁니다. AI abort 실패는 로그만 남기고 삼켜, 로컬 정리를 보장합니다.
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
     * 세션을 종료하되, 정리 과정에서 새로 발생한 예외가 <b>원인 예외({@code cause})를
     * 덮지 않도록</b> {@code cause} 에 suppressed 로 붙이고 삼킵니다.
     *
     * <p>백그라운드 폴링 실패·{@code @Async} 제출 실패처럼 이미 원인 예외가 있는
     * 호출부가 씁니다. 세션 정리 실패보다 원래 실패 원인을 잃지 않는 것이 중요합니다.
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
