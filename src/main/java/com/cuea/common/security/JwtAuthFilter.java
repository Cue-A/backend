package com.cuea.common.security;

import com.cuea.common.exception.BusinessException;
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
 * Authorization 헤더의 access token 을 풀어 {@code userId} 를 요청 속성에 담습니다.
 *
 * <p>토큰이 아예 없으면 통과시킵니다. 로그인이 필요한 엔드포인트는
 * {@link CurrentUser} 파라미터가 401 을 냅니다. 필터에서 경로별 화이트리스트를
 * 관리하면 엔드포인트가 늘 때마다 필터를 고쳐야 합니다.
 *
 * <p>토큰이 있는데 깨졌으면 여기서 바로 끊습니다. 만료면 {@code TOKEN_EXPIRED},
 * 그 외에는 {@code INVALID_TOKEN} 입니다. <b>프론트가 이 둘을 보고 "재발급을
 * 시도할지" 와 "로그인 화면으로 보낼지" 를 나눕니다.</b> 뭉뚱그리면 자동 재발급을
 * 붙일 수 없습니다.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "cuea.userId";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /**
     * 인증 경로는 건너뜁니다.
     *
     * <p><b>이게 없으면 재발급이 구조적으로 불가능합니다.</b> 프론트 인터셉터는
     * 모든 요청에 Authorization 헤더를 붙이므로, 재발급 요청은 정의상 <i>만료된</i>
     * access token 을 달고 옵니다. 그대로 두면 필터가 재발급 요청 자체를 401 로
     * 끊습니다.
     *
     * <p>경로 목록이 아니라 프리픽스 규칙 하나입니다. auth 도메인은 원래 토큰 없이
     * 도는 곳이라 엔드포인트가 늘어도 여기를 고칠 일이 없습니다.
     */
    private static final String AUTH_PREFIX = "/api/auth/";

    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(AUTH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(PREFIX.length()).trim();
        try {
            TokenPayload payload = jwtProvider.parse(token, TokenType.ACCESS);
            request.setAttribute(USER_ID_ATTRIBUTE, payload.userId());
        } catch (BusinessException e) {
            // 필터는 DispatcherServlet 앞이라 GlobalExceptionHandler 가 잡지 못합니다.
            writeError(response, e.getErrorCode());
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.fail(code));
    }
}
