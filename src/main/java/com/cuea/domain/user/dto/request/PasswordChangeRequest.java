package com.cuea.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PasswordChangeRequest(

        @NotBlank
        String currentPassword,

        /** 형식(길이·영문·숫자 포함)은 {@code UserPasswordService} 가 확인해 INVALID_PASSWORD_FORMAT 으로 구분합니다. */
        @NotBlank
        String newPassword
) {
}
