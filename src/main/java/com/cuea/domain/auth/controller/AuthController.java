package com.cuea.domain.auth.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.domain.auth.dto.request.RefreshRequest;
import com.cuea.domain.auth.dto.response.TokenResponse;
import com.cuea.domain.auth.service.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 재발급과 로그아웃.
 *
 * <p><b>회원가입·로그인은 여기 없습니다.</b> 이메일 로그인과 카카오 로그인은
 * 별도 작업이며, 둘 다 {@code TokenService.issue(user)} 를 불러
 * {@link TokenResponse} 를 그대로 반환하면 됩니다.
 *
 * <p>이 경로는 {@code JwtAuthFilter} 를 타지 않습니다. 재발급 요청은 정의상
 * 만료된 access token 을 달고 오기 때문입니다.
 */
@Tag(name = "인증")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TokenService tokenService;

    @Operation(summary = "토큰 재발급",
            description = "access token 이 만료됐을 때(TOKEN_EXPIRED) 호출합니다. "
                    + "refresh token 도 함께 회전하므로 응답의 새 값으로 교체하세요. "
                    + "요청은 하나로 직렬화해야 합니다.")
    @PostMapping("/refresh")
    @RateLimit(key = "auth-refresh", limit = 30, windowSeconds = 60)
    public Result<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.ok(tokenService.refresh(request.refreshToken()));
    }

    @Operation(summary = "로그아웃",
            description = "이 기기의 refresh token 만 끊습니다. access token 은 "
                    + "무상태라 남은 수명(30분)까지 유효합니다.")
    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody RefreshRequest request) {
        tokenService.logout(request.refreshToken());
        return Result.ok();
    }
}
