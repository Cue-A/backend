package com.cuea.domain.auth.service;

import com.cuea.domain.user.entity.Provider;

/**
 * 소셜 제공자에게서 받아온 사용자 정보. 제공자별 응답 형식은 여기서 끝납니다.
 *
 * <p>{@code email} 과 {@code nickname} 은 <b>없을 수 있습니다.</b> 카카오에서
 * 둘 다 선택 동의 항목입니다.
 *
 * @param emailVerified 제공자가 이메일 소유를 검증했는지. 카카오는
 *        {@code kakao_account.is_email_verified} 로 알려줍니다.
 *        <b>이 값이 true 일 때만 기존 계정에 연결할 수 있습니다.</b>
 *        미검증 이메일로 연결하면 남의 이메일 주소만 아는 사람이 그 계정에
 *        올라탈 수 있습니다.
 */
public record OAuthUserInfo(
        Provider provider,
        String providerId,
        String email,
        boolean emailVerified,
        String nickname
) {

    /** 기존 계정에 연결해도 되는지. 아니면 새 계정을 만들어야 합니다. */
    public boolean linkable() {
        return email != null && !email.isBlank() && emailVerified;
    }
}
