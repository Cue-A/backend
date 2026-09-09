package com.cuea.common.security;

import com.cuea.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료와 위조를 구분해서 내보내는지 지킵니다. 프론트는 이 코드를 보고
 * "재발급을 시도할지" 와 "로그인 화면으로 보낼지" 를 나눕니다.
 *
 * <p>Spring 컨텍스트를 띄우지 않습니다.
 */
class JwtAuthFilterTest {

    private static final String SECRET = "test-only-secret-that-is-at-least-32-bytes-long";

    private final JwtProvider jwtProvider = new JwtProvider(
            new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14)));
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtProvider, new ObjectMapper());

    @Test
    void 헤더가_없으면_그냥_통과시킨다() throws Exception {
        MockFilterChain chain = 호출("/api/users/me", null);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void Bearer_형식이_아니면_통과시킨다() throws Exception {
        MockFilterChain chain = 호출("/api/users/me", "Basic abc");

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 유효한_토큰이면_userId_를_요청_속성에_담는다() throws Exception {
        MockHttpServletRequest request = 요청("/api/users/me",
                "Bearer " + jwtProvider.createAccessToken("user-1"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE)).isEqualTo("user-1");
    }

    @Test
    void 만료된_토큰이면_TOKEN_EXPIRED_로_끊는다() throws Exception {
        JwtProvider 만료발급기 = new JwtProvider(
                new JwtProperties(SECRET, Duration.ofMinutes(-1), Duration.ofDays(14)));

        MockHttpServletResponse response = 응답(
                "/api/users/me", "Bearer " + 만료발급기.createAccessToken("user-1"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(ErrorCode.TOKEN_EXPIRED.name());
    }

    @Test
    void 서명이_다르면_INVALID_TOKEN_으로_끊는다() throws Exception {
        JwtProvider 다른키 = new JwtProvider(
                new JwtProperties("another-secret-that-is-also-32-bytes-long!!",
                        Duration.ofMinutes(30), Duration.ofDays(14)));

        MockHttpServletResponse response = 응답(
                "/api/users/me", "Bearer " + 다른키.createAccessToken("user-1"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(ErrorCode.INVALID_TOKEN.name());
    }

    @Test
    void refresh_token_은_access_자리에서_거부된다() throws Exception {
        MockHttpServletResponse response = 응답("/api/users/me",
                "Bearer " + jwtProvider.createRefreshToken("user-1").token());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(ErrorCode.INVALID_TOKEN.name());
    }

    @Test
    void 재발급_경로는_만료된_토큰을_달고_와도_통과시킨다() throws Exception {
        JwtProvider 만료발급기 = new JwtProvider(
                new JwtProperties(SECRET, Duration.ofMinutes(-1), Duration.ofDays(14)));

        MockFilterChain chain = 호출("/api/auth/refresh",
                "Bearer " + 만료발급기.createAccessToken("user-1"));

        assertThat(chain.getRequest())
                .as("여기서 끊으면 재발급이 구조적으로 불가능합니다")
                .isNotNull();
    }

    private MockFilterChain 호출(String uri, String header) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(요청(uri, header), new MockHttpServletResponse(), chain);
        return chain;
    }

    private MockHttpServletResponse 응답(String uri, String header) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(요청(uri, header), response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest 요청(String uri, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (header != null) {
            request.addHeader("Authorization", header);
        }
        return request;
    }
}
