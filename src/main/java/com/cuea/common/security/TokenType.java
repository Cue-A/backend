package com.cuea.common.security;

/**
 * 토큰 종류. JWT 의 {@code typ} 클레임에 실립니다.
 *
 * <p><b>이 구분이 없으면 refresh token 을 access token 자리에 그대로 쓸 수 있습니다.</b>
 * 둘 다 같은 키로 서명하므로 {@code Authorization: Bearer <refreshToken>} 이
 * 서명 검증을 통과해 버립니다. 14일짜리 access token 이 되는 셈입니다.
 */
public enum TokenType {

    ACCESS("access"),
    REFRESH("refresh");

    private final String claim;

    TokenType(String claim) {
        this.claim = claim;
    }

    public String claim() {
        return claim;
    }
}
