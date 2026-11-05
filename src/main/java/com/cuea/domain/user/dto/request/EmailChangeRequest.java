package com.cuea.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailChangeRequest(

        @NotBlank
        @Email
        String newEmail,

        /** LOCAL 인증이 없는(카카오 전용) 계정은 이 API 자체가 거부되므로 항상 필수입니다. */
        @NotBlank
        String currentPassword
) {
}
