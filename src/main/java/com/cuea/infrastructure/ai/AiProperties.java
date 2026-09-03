package com.cuea.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 서버 연동 설정. 주소를 코드에 하드코딩하지 말고 이걸 쓰세요.
 *
 * @param sessionStartTimeout 세션 시작(주질문 여러 개 + TTS). 기본 90초
 * @param answerTimeout       답변 처리(STT + LLM + TTS). 기본 60초
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        String baseUrl,
        String secret,
        Duration sessionStartTimeout,
        Duration answerTimeout,
        Duration pollInterval,
        Duration connectTimeout,
        Duration readTimeout,
        Duration companyCacheTtl,
        Mock mock
) {
    public record Mock(boolean enabled) {
    }
}
