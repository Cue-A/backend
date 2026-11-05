package com.cuea.domain.user.controller;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.user.dto.request.PasswordChangeRequest;
import com.cuea.domain.user.dto.response.UserResponse;
import com.cuea.domain.user.repository.UserRepository;
import com.cuea.domain.user.service.UserPasswordService;
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
    private final UserPasswordService userPasswordService;

    @Operation(summary = "내 정보", description = "Authorization: Bearer <accessToken> 필요")
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Result<UserResponse> me(@CurrentUser String userId) {
        return Result.ok(userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND)));
    }

    @Operation(summary = "비밀번호 변경",
            description = "이메일(LOCAL) 가입 계정만 가능합니다. 카카오 전용 계정은 "
                    + "PASSWORD_NOT_SET 으로 거부됩니다.")
    @PatchMapping("/me/password")
    public Result<Void> changePassword(@CurrentUser String userId,
                                        @Valid @RequestBody PasswordChangeRequest request) {
        userPasswordService.changePassword(userId, request.currentPassword(), request.newPassword());
        return Result.ok();
    }
}
