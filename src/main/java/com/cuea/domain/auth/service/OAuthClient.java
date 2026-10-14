package com.cuea.domain.auth.service;

import com.cuea.domain.user.entity.Provider;

/**
 * 소셜 제공자 연동. 제공자마다 구현체를 하나씩 만듭니다.
 *
 * <p>카카오 구현체는 아직 없습니다. {@code infrastructure/oauth/KakaoOAuthClient}
 * 로 추가하세요.
 *
 * <p><b>인가 코드 방식을 씁니다.</b> 프론트가 카카오에서 받은 {@code code} 를
 * 그대로 넘기면 백엔드가 토큰 교환과 사용자 조회를 합니다. 프론트가 카카오
 * access token 을 직접 받아 넘기는 방식은 쓰지 마세요. 클라이언트 시크릿이
 * 프론트로 나가고, 그 토큰이 정말 우리 앱에서 발급된 것인지 검증할 책임이
 * 우리에게 넘어옵니다.
 */
public interface OAuthClient {

    Provider provider();

    /**
     * @param code        프론트가 제공자에게서 받은 인가 코드
     * @param redirectUri 인가 코드를 받을 때 쓴 것과 <b>같은 값</b>이어야 합니다
     * @throws com.cuea.common.exception.BusinessException 교환·조회 실패 시
     *         {@code OAUTH_FAILED}
     */
    OAuthUserInfo fetch(String code, String redirectUri);
}
