package com.cuea.common.security;

import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
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

/**
 * Authorization 헤더의 JWT 를 풀어 {@code userId} 를 요청 속성에 담습니다.
 *
 * <p>토큰이 아예 없으면 통과시킵니다. 로그인이 필요한 엔드포인트는
 * {@link CurrentUser} 파라미터가 401 을 냅니다.
 * 토큰이 있는데 깨졌으면 여기서 바로 401 로 끊습니다.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "cuea.userId";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String userId = jwtProvider.resolveUserId(header.substring(PREFIX.length()).trim());
            if (userId == null) {
                writeUnauthorized(response);
                return;
            }
            request.setAttribute(USER_ID_ATTRIBUTE, userId);
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.INVALID_TOKEN;
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.fail(code));
    }
}
