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
 *        <b>이 값이 true 일 때만 계정에 이메일을 넣습니다.</b> 검증돼도 같은
 *        이메일의 기존 계정에 연결하지는 않습니다. 기존 계정의 이메일은 소유가
 *        검증된 적이 없기 때문입니다(#139, 02-database.md 연동 규칙).
 */
public record OAuthUserInfo(
        Provider provider,
        String providerId,
        String email,
        boolean emailVerified,
        String nickname
) {

    /** 제공자가 검증한 이메일이 있는지. 있을 때만 그 이메일을 계정에 넣을 수 있습니다. */
    public boolean hasVerifiedEmail() {
        return email != null && !email.isBlank() && emailVerified;
    }
}
