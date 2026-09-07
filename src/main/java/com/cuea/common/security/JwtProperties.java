package com.cuea.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 토큰 설정. 값이 잘못되면 <b>첫 로그인 요청이 아니라 기동 시점에</b> 실패합니다.
 *
 * <p>HS256 은 키가 32바이트 미만이면 jjwt 가 예외를 던지는데, 검증이 없으면 그게
 * 서비스가 뜬 뒤 첫 로그인에서 터집니다. 여기서 막습니다.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(

        @NotBlank
        @Size(min = 32, message = "HS256 시크릿은 최소 32바이트여야 합니다")
        String secret,

        @NotNull Duration accessTokenValidity,

        @NotNull Duration refreshTokenValidity
) {
}
