package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.security.AiSecretFilter;
import com.cuea.infrastructure.ai.dto.AiAnswerSubmitRequest;
import com.cuea.infrastructure.ai.dto.AiErrorResponse;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.cuea.infrastructure.ai.dto.AiSessionStartRequest;
import com.cuea.infrastructure.ai.dto.AiSessionStartResponse;
import com.cuea.infrastructure.ai.dto.AiTaskAcceptedResponse;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 실제 AI 서버를 부릅니다.
 *
 * <p>인증은 JWT 가 아니라 공유 시크릿 헤더입니다. AI 서버는 내부망에 둡니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.mock", name = "enabled", havingValue = "false", matchIfMissing = true)
public class RealAiClient implements AiClient {

    /** 리포트 생성 요청의 멱등 키 헤더. 같은 키면 AI 가 기존 task_id 를 돌려줍니다. */
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final AiErrorTranslator errorTranslator;
    private final ObjectMapper objectMapper;

    public RealAiClient(AiProperties properties,
                        AiErrorTranslator errorTranslator,
                        ObjectMapper objectMapper) {
        this.errorTranslator = errorTranslator;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        // 폴링 한 번은 짧게 끊습니다. 전체 대기 시간은 AiPoller 가 관리합니다.
        factory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader(AiSecretFilter.HEADER, properties.secret())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(status -> status.isError(), (req, res) -> {
                    AiErrorResponse error = readError(res.getBody());
                    log.warn("AI 서버 오류 status={} errorCode={}",
                            res.getStatusCode().value(), error.errorCode());
                    throw errorTranslator.toException(error.errorCode(), error.message());
                })
                .build();
    }

    @Override
    public AiSessionStartResponse startSession(AiSessionStartRequest request) {
        return call(() -> restClient.post()
                .uri("/ai/sessions")
                .body(request)
                .retrieve()
                .body(AiSessionStartResponse.class));
    }

    @Override
    public String submitAnswer(String sessionId, AiAnswerSubmitRequest request) {
        AiTaskAcceptedResponse accepted = call(() -> restClient.post()
                .uri("/ai/sessions/{sessionId}/answers", sessionId)
                .body(request)
                .retrieve()
                .body(AiTaskAcceptedResponse.class));
        return accepted == null ? null : accepted.taskId();
    }

    @Override
    public AiTaskStatusResponse getTask(String taskId) {
        return call(() -> restClient.get()
                .uri("/ai/tasks/{taskId}", taskId)
                .retrieve()
                .body(AiTaskStatusResponse.class));
    }

    @Override
    public void abortSession(String sessionId) {
        try {
            restClient.post()
                    .uri("/ai/sessions/{sessionId}/abort", sessionId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (BusinessException e) {
            // 이미 사라진 세션을 정리하려는 경우가 대부분입니다.
            // 여기서 예외를 올리면 원래 실패 원인을 덮어씁니다.
            log.warn("AI 세션 중단 실패 sessionId={} reason={}", sessionId, e.getMessage());
        }
    }

    @Override
    public String requestReport(String sessionId, String idempotencyKey, AiReportRequest request) {
        AiTaskAcceptedResponse accepted = call(() -> restClient.post()
                .uri("/ai/sessions/{sessionId}/report", sessionId)
                .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .body(request)
                .retrieve()
                .body(AiTaskAcceptedResponse.class));
        return accepted == null ? null : accepted.taskId();
    }

    @Override
    public AiReportTaskStatusResponse getReportTask(String taskId) {
        return call(() -> restClient.get()
                .uri("/ai/tasks/{taskId}", taskId)
                .retrieve()
                .body(AiReportTaskStatusResponse.class));
    }

    /**
     * 연결 실패와 응답 지연을 나눕니다.
     *
     * <p>둘 다 {@link ResourceAccessException} 으로 오지만 사용자에게 할 말이 다릅니다.
     * 연결이 안 되면 503 {@code AI_UNAVAILABLE}, 붙었는데 {@code read-timeout} 안에
     * 답이 없으면 504 {@code AI_TIMEOUT} 입니다.
     *
     * <p>연결 타임아웃도 {@link SocketTimeoutException}("Connect timed out")으로 옵니다.
     * 이건 붙지도 못한 것이라 503 쪽입니다.
     */
    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                log.error("AI 서버 응답이 지연됐습니다", e);
                throw new BusinessException(ErrorCode.AI_TIMEOUT);
            }
            log.error("AI 서버에 연결하지 못했습니다", e);
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }
    }

    private boolean isReadTimeout(ResourceAccessException e) {
        if (!(e.getCause() instanceof SocketTimeoutException timeout)) {
            return false;
        }
        String message = timeout.getMessage();
        return message == null || !message.toLowerCase(Locale.ROOT).contains("connect");
    }

    private AiErrorResponse readError(InputStream body) {
        try {
            return objectMapper.readValue(body, AiErrorResponse.class);
        } catch (Exception e) {
            return new AiErrorResponse(null, null);
        }
    }
}
