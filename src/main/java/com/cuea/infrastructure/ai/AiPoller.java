package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * task_id 를 1초 간격으로 물어보고 결과가 나올 때까지 기다립니다.
 *
 * <p>작업 종류를 모르게 설계했습니다. 리포트 계약이 도착하면 그대로 재사용합니다.
 * docs/13-report.md 참고.
 *
 * <p><b>트랜잭션 안에서 호출하지 마세요.</b> 최대 90초 동안 DB 커넥션을 붙듭니다.
 * 가상 스레드가 켜져 있으므로 블로킹으로 기다려도 됩니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPoller {

    private final AiClient aiClient;
    private final AiProperties properties;
    private final AiErrorTranslator errorTranslator;

    /**
     * @param onProgress 폴링 중 stage 가 바뀔 때마다 호출됩니다. WebSocket push 용.
     * @throws BusinessException 타임아웃({@code AI_TIMEOUT})이거나 AI 가 실패를 알려준 경우
     */
    public AiTaskStatusResponse await(String taskId,
                                      Duration timeout,
                                      Consumer<String> onProgress) {
        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plus(timeout);
        String lastStage = null;

        while (Instant.now().isBefore(deadline)) {
            AiTaskStatusResponse status = aiClient.getTask(taskId);

            if (status.isDone()) {
                log.info("AI 작업 완료 taskId={} elapsedMs={}",
                        taskId, Duration.between(startedAt, Instant.now()).toMillis());
                return status;
            }
            if (status.isFailed()) {
                log.warn("AI 작업 실패 taskId={} errorCode={} elapsedMs={}",
                        taskId, status.errorCode(),
                        Duration.between(startedAt, Instant.now()).toMillis());
                throw errorTranslator.toException(status.errorCode(), status.message());
            }

            if (onProgress != null && status.stage() != null && !status.stage().equals(lastStage)) {
                lastStage = status.stage();
                onProgress.accept(lastStage);
            }

            sleep(properties.pollInterval());
        }

        log.error("AI 작업 타임아웃 taskId={} timeoutMs={}", taskId, timeout.toMillis());
        throw new BusinessException(ErrorCode.AI_TIMEOUT);
    }

    public AiTaskStatusResponse await(String taskId, Duration timeout) {
        return await(taskId, timeout, null);
    }

    private void sleep(Duration interval) {
        try {
            Thread.sleep(interval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 대기가 중단되었습니다", e);
        }
    }
}
