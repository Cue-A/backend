package com.cuea.domain.user.controller;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.user.dto.response.UserResponse;
import com.cuea.domain.user.repository.UserRepository;
import com.cuea.domain.user.service.UserWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final UserWithdrawalService userWithdrawalService;

    @Operation(summary = "내 정보", description = "Authorization: Bearer <accessToken> 필요")
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Result<UserResponse> me(@CurrentUser String userId) {
        return Result.ok(userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND)));
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
