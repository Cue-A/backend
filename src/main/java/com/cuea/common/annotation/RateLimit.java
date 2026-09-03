package com.cuea.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 고정 윈도 호출 제한. Redis Lua 스크립트로 셉니다.
 *
 * <p>세션 시작·답변 제출처럼 AI 비용이 드는 엔드포인트에 붙입니다.
 *
 * <pre>{@code
 * @RateLimit(key = "session-start", limit = 10, windowSeconds = 60)
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 카운터 이름. 엔드포인트마다 다르게 줍니다. */
    String key();

    /** 윈도 안에서 허용할 호출 수. */
    int limit() default 30;

    /** 윈도 길이(초). */
    int windowSeconds() default 60;
}
