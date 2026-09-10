package com.cuea.common.security;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code supportsParameter} 가 타입까지 보면, 타입이 안 맞을 때 이 리졸버가 조용히
 * 빠지고 Spring 기본 리졸버가 <b>쿼리 파라미터로</b> 값을 채웁니다. 그러면
 * {@code @CurrentUser Long userId} 가 {@code ?userId=999} 로 뚫립니다.
 * 그래서 애노테이션만 보고 지원하고, 타입은 예외로 막습니다.
 */
class CurrentUserArgumentResolverTest {

    private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();
    private final ServletWebRequest webRequest = new ServletWebRequest(new MockHttpServletRequest());

    @Test
    void 타입과_무관하게_애노테이션만_보고_지원한다() {
        assertThat(resolver.supportsParameter(파라미터("문자열", 0))).isTrue();
        assertThat(resolver.supportsParameter(파라미터("숫자", 0))).isTrue();
    }

    @Test
    void 애노테이션이_없으면_지원하지_않는다() {
        assertThat(resolver.supportsParameter(파라미터("애노테이션없음", 0))).isFalse();
    }

    @Test
    void String_이_아닌_타입은_예외로_막는다() {
        assertThatThrownBy(() -> resolve(파라미터("숫자", 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("String");
    }

    @Test
    void 토큰이_없으면_UNAUTHORIZED_를_낸다() {
        assertThatThrownBy(() -> resolve(파라미터("문자열", 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void required_false_면_토큰이_없어도_null_을_준다() {
        assertThatCode(() -> assertThat(resolve(파라미터("선택", 0))).isNull())
                .doesNotThrowAnyException();
    }

    @Test
    void 요청_속성의_userId_를_그대로_돌려준다() {
        webRequest.getRequest().setAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE, "user-1");

        assertThat(resolve(파라미터("문자열", 0))).isEqualTo("user-1");
    }

    private Object resolve(MethodParameter parameter) {
        return resolver.resolveArgument(parameter, null, webRequest, null);
    }

    private MethodParameter 파라미터(String methodName, int index) {
        Method method = java.util.Arrays.stream(샘플.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return new MethodParameter(method, index);
    }

    @SuppressWarnings("unused")
    static class 샘플 {
        void 문자열(@CurrentUser String userId) {
        }

        void 숫자(@CurrentUser Long userId) {
        }

        void 선택(@CurrentUser(required = false) String userId) {
        }

        void 애노테이션없음(String userId) {
        }
    }
}
