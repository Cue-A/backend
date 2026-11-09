package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.user.dto.request.ProfileUpdateRequest;
import com.cuea.domain.user.dto.response.ProfileResponse;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.entity.UserProfile;
import com.cuea.domain.user.repository.UserProfileRepository;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 마이페이지 "내정보" 조회/수정. {@code user_profile} 행은 가입 시점에 만들지 않고
 * 사용자가 처음 저장할 때 만듭니다({@link UserProfile} 문서 참고).
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    /** 2026-11-05 확정: 다중 경력 히스토리가 아니라 단일 레벨 4종만 허용합니다. */
    private static final Set<String> ALLOWED_CAREER_LEVELS =
            Set.of("student", "entry", "junior", "experienced");

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(String userId) {
        User user = findUser(userId);
        UserProfile profile = userProfileRepository.findByUser_UserId(userId).orElse(null);
        return ProfileResponse.of(user, profile);
    }

    @Transactional
    public ProfileResponse updateProfile(String userId, ProfileUpdateRequest request) {
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "수정할 프로필 정보가 없습니다");
        }
        if (request.careerLevel() != null && !ALLOWED_CAREER_LEVELS.contains(request.careerLevel())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "허용되지 않은 careerLevel 값입니다");
        }
        if (request.userName() != null && request.userName().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "userName은 빈 값일 수 없습니다");
        }

        User user = findUser(userId);
        if (request.userName() != null) {
            user.changeNickname(request.userName());
        }

        UserProfile profile = userProfileRepository.findByUser_UserId(userId)
                .orElseGet(() -> userProfileRepository.save(UserProfile.builder().user(user).build()));
        profile.update(request.introduction(), request.targetJobRole(), request.careerLevel(),
                request.schoolName(), request.major(), request.graduationYear());

        return ProfileResponse.of(user, profile);
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
