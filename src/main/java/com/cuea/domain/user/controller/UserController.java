package com.cuea.domain.user.controller;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.user.dto.request.ProfileUpdateRequest;
import com.cuea.domain.user.dto.response.ProfileResponse;
import com.cuea.domain.user.dto.response.UserResponse;
import com.cuea.domain.user.repository.UserRepository;
import com.cuea.domain.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final UserProfileService userProfileService;

    @Operation(summary = "내 정보", description = "Authorization: Bearer <accessToken> 필요")
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Result<UserResponse> me(@CurrentUser String userId) {
        return Result.ok(userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND)));
    }

    @Operation(summary = "프로필 조회", description = "마이페이지 \"내정보\". GET /api/users/me 와는 별개입니다.")
    @GetMapping("/me/profile")
    public Result<ProfileResponse> profile(@CurrentUser String userId) {
        return Result.ok(userProfileService.getProfile(userId));
    }

    @Operation(summary = "프로필 수정",
            description = "보낸 필드만 반영합니다. email·profileImageUrl 은 이 API로 바뀌지 않습니다.")
    @PatchMapping("/me/profile")
    public Result<ProfileResponse> updateProfile(@CurrentUser String userId,
                                                   @Valid @RequestBody ProfileUpdateRequest request) {
        return Result.ok(userProfileService.updateProfile(userId, request));
    }
}
