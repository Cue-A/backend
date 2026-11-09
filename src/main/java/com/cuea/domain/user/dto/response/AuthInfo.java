package com.cuea.domain.user.dto.response;

import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.UserAuth;

import java.time.LocalDateTime;

public record AuthInfo(
        Provider provider,
        boolean hasPassword,
        LocalDateTime linkedAt
) {

    public static AuthInfo from(UserAuth auth) {
        return new AuthInfo(auth.getProvider(), auth.getPassword() != null, auth.getCreatedAt());
    }
}
