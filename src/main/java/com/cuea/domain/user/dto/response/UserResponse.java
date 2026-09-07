package com.cuea.domain.user.dto.response;

import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.User;

import java.util.List;

/**
 * 사용자 정보. 로그인 응답에도 그대로 실립니다.
 *
 * <p>{@code providers} 가 배열인 이유는 계정 연동을 지원하기 때문입니다.
 * 마이페이지에서 "카카오 연결됨" 을 띄우려면 프론트가 이걸 알아야 합니다.
 *
 * <p>{@code email} 은 카카오 이메일 미동의 시 {@code null} 입니다.
 */
public record UserResponse(
        String userId,
        String nickname,
        String email,
        List<Provider> providers
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getUserId(), user.getNickname(),
                user.getEmail(), user.providers());
    }
}
