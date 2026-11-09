package com.cuea.domain.user.dto.response;

import com.cuea.domain.user.entity.User;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 계정 설정 화면용 조회 응답. {@code password} 원문·해시와 {@code providerId}
 * (카카오 회원번호)는 절대 담지 않습니다 — {@link AuthInfo#hasPassword} 만 내려줍니다.
 */
public record AccountResponse(
        String email,
        LocalDateTime createdAt,
        List<AuthInfo> auths
) {

    public static AccountResponse from(User user) {
        List<AuthInfo> auths = user.getAuths().stream().map(AuthInfo::from).toList();
        return new AccountResponse(user.getEmail(), user.getCreatedAt(), auths);
    }
}
