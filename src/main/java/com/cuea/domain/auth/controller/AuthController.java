package com.cuea.domain.auth.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.PublicApi;
import com.cuea.domain.auth.dto.request.KakaoLoginRequest;
import com.cuea.domain.auth.dto.request.LoginRequest;
import com.cuea.domain.auth.dto.request.RefreshRequest;
import com.cuea.domain.auth.dto.request.SignupRequest;
import com.cuea.domain.auth.dto.response.TokenResponse;
import com.cuea.domain.auth.service.AuthService;
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
 * 회원가입·로그인·토큰 재발급·로그아웃. 이메일·카카오 로그인 모두
 * {@code TokenService.issue(user)} 를 그대로 불러 {@link TokenResponse} 를 반환합니다 —
 * 토큰 발급·회전 로직은 여기서 다시 만들지 않습니다.
 *
 * <p>이 경로는 전부 {@code JwtAuthFilter} 를 타지 않습니다. 로그인 전 API 인
 * signup·login·oauth/kakao 는 토큰이 없는 게 당연하고, refresh 요청은 정의상
 * 만료된 access token 을 달고 오기 때문입니다.
 */
@Tag(name = "인증")
@PublicApi("회원가입·로그인은 정의상 토큰 없이 들어오고, 재발급·로그아웃은 만료된 "
        + "access token 을 달고 들어옵니다. refresh token 자체가 자격증명이라 본문에서 검증합니다.")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;

    @Operation(summary = "이메일 회원가입",
            description = "provider=LOCAL 로 가입합니다. 성공하면 바로 로그인 상태의 "
                    + "TokenResponse 를 돌려줘 프론트가 이어서 로그인을 호출할 필요가 없습니다.")
    @PostMapping("/signup")
    @RateLimit(key = "auth-signup", limit = 5, windowSeconds = 60)
    public Result<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
        return Result.ok(authService.signup(request));
    }

    @Operation(summary = "이메일 로그인",
            description = "이메일이 없거나 비밀번호가 틀려도 항상 같은 INVALID_CREDENTIALS 를 "
                    + "반환합니다. 어느 쪽이 틀렸는지는 알려주지 않습니다.")
    @PostMapping("/login")
    @RateLimit(key = "auth-login", limit = 10, windowSeconds = 60)
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @Operation(summary = "카카오 로그인",
            description = "인가 코드 방식입니다. provider_id 로 이미 연결된 계정이 있으면 로그인, "
                    + "없으면 이메일이 검증된 경우에 한해 기존 이메일 계정에 연결하고, 그마저 없으면 "
                    + "새로 가입합니다. 신규 가입 여부는 응답의 isNewUser 로 내려줍니다.")
    @PostMapping("/oauth/kakao")
    @RateLimit(key = "auth-kakao", limit = 10, windowSeconds = 60)
    public Result<TokenResponse> loginWithKakao(@Valid @RequestBody KakaoLoginRequest request) {
        return Result.ok(authService.loginWithKakao(request));
    }

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
