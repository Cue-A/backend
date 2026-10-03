package com.cuea.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(

        @NotBlank
        @Email
        String email,

        /** 형식(길이·영문·숫자 포함)은 {@code AuthService} 가 확인해 INVALID_PASSWORD_FORMAT 으로 구분합니다. */
        @NotBlank
        String password,

        @NotBlank
        @Size(max = 50)
        String nickname
) {
}
