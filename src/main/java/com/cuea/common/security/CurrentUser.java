package com.cuea.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로그인한 사용자 ID 를 컨트롤러 파라미터로 받습니다.
 *
 * <pre>{@code
 * public Result<UserResponse> me(@CurrentUser String userId) { ... }
 * }</pre>
 *
 * 토큰이 없으면 {@code UNAUTHORIZED} 가 납니다.
 * 인증이 선택인 엔드포인트는 {@code required = false} 로 두세요.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
    boolean required() default true;
}
