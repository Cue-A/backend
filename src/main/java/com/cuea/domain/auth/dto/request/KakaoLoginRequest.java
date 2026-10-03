package com.cuea.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * @param authorizationCode 프론트가 카카오에서 받은 인가 코드
 * @param redirectUri       인가 코드를 받을 때 쓴 것과 같은 값이어야 합니다
 */
public record KakaoLoginRequest(

        @NotBlank
        String authorizationCode,

        @NotBlank
        String redirectUri
) {
}
