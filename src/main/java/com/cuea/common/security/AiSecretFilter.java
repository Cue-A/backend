package com.cuea.common.security;

import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
import com.cuea.infrastructure.ai.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * AI 서버가 Spring 을 호출할 때 쓰는 내부 경로({@code /api/internal/**})를 지킵니다.
 * 내부 통신이라 JWT 대신 공유 시크릿 헤더({@code X-CueA-Secret})를 씁니다.
 *
 * <p>현재 내부 엔드포인트는 없습니다. AI 파트가 콜백을 요구하면 이 경로 아래에
 * 만드세요. 나가는 방향 헤더는 {@code RealAiClient} 가 붙입니다.
 */
@Component
@Order(5)
@RequiredArgsConstructor
public class AiSecretFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-CueA-Secret";
    private static final String PROTECTED_PREFIX = "/api/internal/";

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (!matches(provided, aiProperties.secret())) {
            ErrorCode code = ErrorCode.FORBIDDEN;
            response.setStatus(code.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), Result.fail(code));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
