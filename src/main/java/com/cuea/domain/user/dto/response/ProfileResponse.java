package com.cuea.domain.user.dto.response;

import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.entity.UserProfile;

/**
 * 마이페이지 "내정보" 조회 응답. {@code email}은 표시만 합니다 — 수정은
 * 계정 설정({@code PATCH /me/email})에서만 가능합니다.
 *
 * <p>{@code user_profile} 행이 아직 없는 사용자(가입 후 한 번도 프로필을 저장한 적
 * 없음)는 {@code profile} 이 {@code null} 로 들어와 선택 필드가 전부 {@code null} 입니다.
 */
public record ProfileResponse(
        String userId,
        String userName,
        String email,
        String profileImageUrl,
        String introduction,
        String targetJobRole,
        String careerLevel,
        String schoolName,
        String major,
        Integer graduationYear
) {

    public static ProfileResponse of(User user, UserProfile profile) {
        if (profile == null) {
            return new ProfileResponse(user.getUserId(), user.getNickname(), user.getEmail(),
                    null, null, null, null, null, null, null);
        }
        return new ProfileResponse(user.getUserId(), user.getNickname(), user.getEmail(),
                profile.getProfileImageUrl(), profile.getIntroduction(), profile.getTargetJobRole(),
                profile.getCareerLevel(), profile.getSchoolName(), profile.getMajor(),
                profile.getGraduationYear());
    }
}
