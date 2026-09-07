package com.cuea.common.exception;

import com.cuea.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 클라이언트 잘못(4xx)을 catch-all 이 500 으로 바꾸지 않는지 지킵니다.
 *
 * <p>Spring 컨텍스트를 띄우지 않고 핸들러를 직접 호출합니다.
 * 이 저장소의 테스트는 DB·Redis 없이 돌아야 합니다.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 없는_경로는_500_이_아니라_404_다() {
        ResponseEntity<Result<Void>> response =
                handler.handleNoResource(new NoResourceFoundException(HttpMethod.GET, "api/users/me"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.PATH_NOT_FOUND.name());
    }

    @Test
    void 지원하지_않는_메서드는_405_다() {
        ResponseEntity<Result<Void>> response =
                handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("GET"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.name());
    }

    @Test
    void 깨진_요청_본문은_400_이다() {
        ResponseEntity<Result<Void>> response = handler.handleNotReadable(notReadable("{\"nickname\":"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST.name());
    }

    /**
     * 요청 본문에는 이력서 내용·면접 답변이 담깁니다. 파싱에 실패했다고
     * 그 원문을 응답으로 되돌려주면 안 됩니다. {@code JwtProvider} 가 토큰 원문을
     * 로그에 남기지 않는 것과 같은 이유입니다.
     */
    @Test
    void 깨진_본문의_원문이_응답에_새어나가지_않는다() {
        String secret = "지원자의-이력서-내용";
        ResponseEntity<Result<Void>> response =
                handler.handleNotReadable(notReadable("{\"resume\":\"" + secret + "\""));

        assertThat(response.getBody().message()).doesNotContain(secret);
    }

    @Test
    void 파라미터_타입이_맞지_않으면_400_이다() throws Exception {
        ResponseEntity<Result<Void>> response = handler.handleTypeMismatch(typeMismatch());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST.name());
    }

    @Test
    void 필수_쿼리_파라미터가_없으면_400_이다() {
        ResponseEntity<Result<Void>> response = handler.handleBindingFailure(
                new MissingServletRequestParameterException("questionCount", "int"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST.name());
    }

    @Test
    void 필수_헤더가_없으면_400_이다() throws Exception {
        Method method = getClass().getDeclaredMethod("파라미터를_받는_가짜_메서드", int.class);
        ResponseEntity<Result<Void>> response = handler.handleBindingFailure(
                new MissingRequestHeaderException("X-CueA-Secret", new MethodParameter(method, 0)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST.name());
    }

    /**
     * 두 예외 모두 {@link ServletRequestBindingException} 하위라 핸들러 하나로 받습니다.
     * 상위 타입이 바뀌면 이 테스트가 먼저 깨집니다.
     */
    @Test
    void 파라미터_누락과_헤더_누락은_같은_상위타입이다() {
        assertThat(ServletRequestBindingException.class)
                .isAssignableFrom(MissingServletRequestParameterException.class)
                .isAssignableFrom(MissingRequestHeaderException.class);
    }

    @Test
    void 진짜_예상치_못한_예외는_그대로_500_이다() {
        ResponseEntity<Result<Void>> response =
                handler.handleUnexpected(new IllegalStateException("커넥션 풀 고갈"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
    }

    @Test
    void 비즈니스_예외는_지정한_상태로_나간다() {
        ResponseEntity<Result<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.SESSION_NOT_FOUND.name());
    }

    private HttpMessageNotReadableException notReadable(String body) {
        return new HttpMessageNotReadableException(
                "JSON parse error: " + body,
                new MockHttpInputMessage(body.getBytes(StandardCharsets.UTF_8)));
    }

    private MethodArgumentTypeMismatchException typeMismatch() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("파라미터를_받는_가짜_메서드", int.class);
        return new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "questionCount",
                new MethodParameter(method, 0), new NumberFormatException());
    }

    @SuppressWarnings("unused")
    private void 파라미터를_받는_가짜_메서드(int questionCount) {
    }
}
