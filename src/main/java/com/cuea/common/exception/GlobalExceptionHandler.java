package com.cuea.common.exception;

import com.cuea.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 모든 예외를 {@link Result} 형태로 바꿔 내보냅니다.
 *
 * <p><b>클라이언트 잘못(4xx)은 반드시 개별 핸들러를 두세요.</b> 맨 아래
 * {@code handleUnexpected} 가 {@code Exception} 을 전부 받기 때문에, 핸들러가 없는
 * 예외는 종류를 가리지 않고 500 + ERROR 로그가 됩니다. 경로 오타 하나가 서버 장애로
 * 보이고, 스캐너 봇이 랜덤 경로를 긁으면 ERROR 로그에 묻혀 진짜 장애를 놓칩니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        if (code.getStatus().is5xxServerError()) {
            log.error("비즈니스 예외 code={}", code, e);
        } else {
            log.warn("비즈니스 예외 code={} message={}", code, e.getMessage());
        }
        return ResponseEntity.status(code.getStatus())
                .body(Result.fail(code.name(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(Result.fail(ErrorCode.INVALID_REQUEST.name(), message));
    }

    /** 매핑되지 않은 경로. 프론트 URL 오타가 대부분이라 DEBUG 로만 남깁니다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        log.debug("없는 경로 path={}", e.getResourcePath());
        return respond(ErrorCode.PATH_NOT_FOUND, ErrorCode.PATH_NOT_FOUND.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("지원하지 않는 메서드 method={}", e.getMethod());
        return respond(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage());
    }

    /**
     * 본문을 파싱하지 못한 경우.
     *
     * <p>예외 메시지에 요청 본문 원문이 그대로 들어 있습니다. 본문에는 이력서 내용과
     * 면접 답변이 담기므로 <b>응답에도 로그에도 옮기지 않습니다.</b>
     * {@code JwtProvider} 가 토큰 원문을 남기지 않는 것과 같은 이유입니다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("요청 본문을 읽지 못했습니다 reason={}", e.getClass().getSimpleName());
        return respond(ErrorCode.INVALID_REQUEST, "요청 본문을 읽을 수 없습니다");
    }

    /**
     * 필수 쿼리 파라미터나 필수 헤더가 빠진 경우.
     *
     * <p>{@code MissingServletRequestParameterException} 과
     * {@code MissingRequestHeaderException} 이 모두 이 타입 아래에 있어 하나로 받습니다.
     * 어느 값이 빠졌는지는 알려주되, 값 자체는 담지 않습니다.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<Result<Void>> handleBindingFailure(ServletRequestBindingException e) {
        log.warn("요청 바인딩 실패 reason={}", e.getClass().getSimpleName());
        return respond(ErrorCode.INVALID_REQUEST, "필수 값이 빠졌습니다");
    }

    /** {@code ?questionCount=abc} 처럼 파라미터 타입이 맞지 않는 경우. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("파라미터 타입이 맞지 않습니다 name={}", e.getName());
        return respond(ErrorCode.INVALID_REQUEST, e.getName() + ": 형식이 올바르지 않습니다");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(Result.fail(code));
    }

    private ResponseEntity<Result<Void>> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(Result.fail(code.name(), message));
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
