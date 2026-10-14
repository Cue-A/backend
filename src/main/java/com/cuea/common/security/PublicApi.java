package com.cuea.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증 없이 열어두는 엔드포인트임을 표시합니다.
 *
 * <p>{@code JwtAuthFilter} 는 토큰이 없으면 그냥 통과시키므로, 인증이 필요한
 * 핸들러에 {@link CurrentUser} 를 빠뜨리면 그 API 는 조용히 열립니다.
 * {@code EndpointAuthGuardTest} 가 모든 핸들러에 대해 <b>{@code @PublicApi} 이거나
 * {@code @CurrentUser} 를 받거나</b> 둘 중 하나를 강제합니다. 새 엔드포인트를
 * 추가하고 아무것도 고르지 않으면 빌드가 깨집니다.
 *
 * <p>화이트리스트를 필터가 아니라 테스트에 두는 이유는, 필터에 경로 목록을 두면
 * 엔드포인트가 늘 때마다 필터를 고쳐야 하기 때문입니다.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {

    /** 왜 인증 없이 열어도 되는지. 리뷰에서 이 문장을 봅니다. */
    String value();
}
