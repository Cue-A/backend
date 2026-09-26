package com.cuea.common.config;

import com.cuea.infrastructure.websocket.ReportSocketHandler;
import com.cuea.infrastructure.websocket.SessionSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 폴링 결과를 프론트에 밀어주는 채널.
 * 메시지 스키마는 백엔드가 정합니다. AI 응답 형식을 그대로 쓰지 않습니다.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionSocketHandler sessionSocketHandler;
    private final ReportSocketHandler reportSocketHandler;
    private final CorsProperties corsProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] allowedOrigins = corsProperties.allowedOrigins().toArray(String[]::new);
        registry.addHandler(sessionSocketHandler, "/ws/interviews/{sessionId}")
                .setAllowedOrigins(allowedOrigins);
        registry.addHandler(reportSocketHandler, "/ws/reports/{reportId}")
                .setAllowedOrigins(allowedOrigins);
    }
}
