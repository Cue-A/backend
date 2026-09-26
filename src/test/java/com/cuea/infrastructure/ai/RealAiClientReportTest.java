package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.dto.AiReportAnswer;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.ai.dto.AiReportTaskStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 HTTP 로 AI 계약을 확인합니다. 목으로는 헤더 이름·경로·snake_case 직렬화·
 * 타임아웃 구분을 볼 수 없습니다. JDK 내장 {@link HttpServer} 라 추가 의존성이 없습니다.
 */
class RealAiClientReportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private ServerSocket silentSocket;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.stop(0);
        }
        if (silentSocket != null) {
            silentSocket.close();
        }
    }

    @Test
    void 리포트_요청은_멱등_키_헤더와_snake_case_본문으로_보낸다() throws IOException {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> secret = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        startServer(exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            secret.set(exchange.getRequestHeaders().getFirst("X-Cueanda-Secret"));
            path.set(exchange.getRequestURI().getPath());
            body.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 202, "{\"task_id\":\"task_r1\"}");
        });

        String taskId = client(Duration.ofSeconds(2)).requestReport("sess_1", "rpt_sess_1_1", request());

        assertThat(taskId).isEqualTo("task_r1");
        assertThat(path.get()).isEqualTo("/ai/sessions/sess_1/report");
        assertThat(key.get()).isEqualTo("rpt_sess_1_1");
        assertThat(secret.get()).isEqualTo("secret");
        JsonNode answer = body.get().path("answers").get(0);
        assertThat(body.get().path("job_role").asText()).isEqualTo("백엔드 개발");
        assertThat(answer.path("question_id").asText()).isEqualTo("q_1");
        assertThat(answer.path("is_timeout").asBoolean()).isFalse();
        assertThat(answer.path("is_replay").asBoolean()).isTrue();
        assertThat(answer.path("is_spare_topic").asBoolean()).isFalse();
        // 영상이 없으면 키를 빼지 않고 null 로 보냅니다. 계약이 null 을 skipped 로 해석합니다.
        assertThat(answer.has("video_url")).isTrue();
        assertThat(answer.path("video_url").isNull()).isTrue();
    }

    @Test
    void AI_가_422_REPORT_TOO_SHORT_를_주면_그대로_옮긴다() throws IOException {
        startServer(exchange -> respond(exchange, 422,
                "{\"error_code\":\"REPORT_TOO_SHORT\",\"message\":\"답변이 부족합니다\"}"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).requestReport("sess_1", "rpt_sess_1_1", request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REPORT_TOO_SHORT);
    }

    @Test
    void 리포트_task_결과는_원본_JSON_으로_받는다() throws IOException {
        startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"done\",\"result\":{\"report_status\":\"partial\",\"overall\":{\"score\":68}}}"));

        AiReportTaskStatusResponse status = client(Duration.ofSeconds(2)).getReportTask("task_r1");

        assertThat(status.isDone()).isTrue();
        assertThat(status.result().path("overall").path("score").asInt()).isEqualTo(68);
    }

    /** 붙었는데 답이 없으면 504. 연결 실패(503)와 사용자 안내가 다릅니다. */
    @Test
    void 응답이_read_timeout_을_넘기면_AI_TIMEOUT() throws IOException {
        // accept 만 하고 아무것도 보내지 않는 소켓. 연결은 되고 응답은 오지 않습니다.
        silentSocket = new ServerSocket(0);

        RealAiClient client = client("http://localhost:" + silentSocket.getLocalPort(), Duration.ofMillis(300));

        assertThatThrownBy(() -> client.getReportTask("task_r1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_TIMEOUT);
    }

    @Test
    void 연결이_안_되면_AI_UNAVAILABLE() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        RealAiClient client = client("http://localhost:" + closedPort, Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.getReportTask("task_r1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_UNAVAILABLE);
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private RealAiClient client(Duration readTimeout) {
        return client("http://localhost:" + server.getAddress().getPort(), readTimeout);
    }

    private RealAiClient client(String baseUrl, Duration readTimeout) {
        AiProperties properties = new AiProperties(
                baseUrl, "secret",
                Duration.ofSeconds(90), Duration.ofSeconds(60), Duration.ZERO,
                Duration.ofSeconds(2), readTimeout,
                new AiProperties.Mock(false));
        return new RealAiClient(properties, new AiErrorTranslator(), objectMapper);
    }

    private AiReportRequest request() {
        return new AiReportRequest("pressure", "백엔드 개발", null, null, List.of(
                new AiReportAnswer("q_1", "question", "지원 동기를 말씀해 주세요", "지원동기", "L1", 1,
                        "https://s3/a.webm", null, false, null, true, false)));
    }

    private void respond(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
