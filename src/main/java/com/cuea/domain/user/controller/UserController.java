package com.cuea.domain.user.controller;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.user.dto.request.PasswordChangeRequest;
import com.cuea.domain.user.dto.response.UserResponse;
import com.cuea.domain.user.repository.UserRepository;
import com.cuea.domain.user.service.UserPasswordService;
import com.cuea.domain.user.dto.response.AccountResponse;
import com.cuea.domain.user.service.UserAccountService;
import com.cuea.domain.user.service.UserWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final UserAccountService userAccountService;
    private final UserWithdrawalService userWithdrawalService;

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

    @Operation(summary = "계정 정보 조회",
            description = "가입일과 로그인 수단별(이메일·카카오) 연결 정보를 보여줍니다. "
                    + "비밀번호 원문·해시, 카카오 회원번호는 내려주지 않습니다.")
    @GetMapping("/me/account")
    public Result<AccountResponse> account(@CurrentUser String userId) {
        return Result.ok(userAccountService.getAccount(userId));
    }

    @Operation(summary = "회원 탈퇴",
            description = "계정과 모든 연관 데이터(문서·면접 기록·리포트 등)를 영구 삭제합니다. "
                    + "되돌릴 수 없습니다. 이 기기뿐 아니라 로그인된 모든 기기의 refresh token도 함께 폐기됩니다.")
    @DeleteMapping("/me")
    public Result<Void> withdraw(@CurrentUser String userId) {
        userWithdrawalService.withdraw(userId);
        return Result.ok();
    }
}
