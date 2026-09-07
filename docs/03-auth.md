# 03. 인증

토큰 정책과 로그인 API 계약. **이메일·카카오 로그인을 구현하기 전에 읽으세요.**

---

## 토큰

| | 수명 | 서버 저장 | 클레임 |
|---|---|---|---|
| access | **30분** | 없음 (무상태) | `sub`=userId, `typ`=access |
| refresh | **14일** | Redis | `sub`, `typ`=refresh, `jti` |

**`typ` 클레임을 반드시 확인합니다.** 둘 다 같은 키로 서명하므로, 없으면
`Authorization: Bearer <refreshToken>` 이 그대로 통과해 14일짜리 access token 이
됩니다. `JwtProvider.parse(token, TokenType.X)` 로만 검증하세요.

**access 는 저장하지 않습니다.** 그래서 **로그아웃해도 남은 수명(30분)까지는
유효합니다.** 전 요청에 Redis 조회를 붙이는 블랙리스트 대신, 만료를 짧게 잡는
쪽을 택했습니다.

### refresh 저장 구조

```
refresh:{userId}  →  Hash { jti_A: 발급시각, jti_B: 발급시각 }   TTL 14일
                            └ 노트북        └ 폰
```

해시 하나로 셋이 다 됩니다 — **다기기 로그인**(필드 여러 개), **로그아웃**(필드
하나 삭제), **재사용 탐지 시 전 기기 폐기**(키 삭제).

**토큰 원문은 저장하지 않고 `jti` 만 넣습니다.** Redis 가 통째로 새어도 그것만으로는
토큰을 만들 수 없습니다. 서명 키가 있어야 합니다.

### 회전과 재사용 탐지

재발급할 때마다 refresh 도 새것으로 바뀝니다(RTR). 이미 회전된 토큰이 다시 오면
탈취로 보고 **그 사용자의 모든 기기를 끊습니다.**

`resources/scripts/refresh_rotate.lua` 가 한 덩어리로 처리합니다. "확인 → 삭제 →
등록" 사이에 다른 요청이 끼어들면 멀쩡한 사용자가 재사용으로 몰리기 때문입니다.

> ⚠️ **프론트는 재발급 요청을 하나로 직렬화해야 합니다.**
> 병렬 요청 두 개가 동시에 401 을 받고 각자 재발급하면, 늦게 도착한 쪽이 이미
> 회전된 토큰을 들고 와 **재사용으로 판정되어 강제 로그아웃됩니다.**
> 진행 중인 재발급이 있으면 같은 Promise 를 재사용하세요.

---

## API

```
POST /api/auth/signup          이메일 가입    → Result<TokenResponse>   (미구현)
POST /api/auth/login           이메일 로그인  → Result<TokenResponse>   (미구현)
POST /api/auth/oauth/kakao     카카오        → Result<TokenResponse>   (미구현)
POST /api/auth/refresh         재발급        → Result<TokenResponse>
POST /api/auth/logout          로그아웃      → Result<Void>
GET  /api/users/me             내 정보       → Result<UserResponse>
```

**로그인 세 경로가 전부 같은 `TokenResponse` 를 반환합니다.**

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "tokenType": "Bearer",
    "expiresIn": 1800,
    "user": {
      "userId": "...", "nickname": "김취준",
      "email": "kim@example.com", "providers": ["LOCAL", "KAKAO"]
    }
  },
  "errorCode": null, "message": null
}
```

`user` 를 같이 싣는 이유는 로그인 직후 `/api/users/me` 를 한 번 더 부르지 않게
하려는 것입니다. `providers` 가 배열인 것은 계정 연동을 지원하기 때문입니다.
`email` 은 카카오 이메일 미동의 시 `null` 입니다.

`/api/auth/**` 는 `JwtAuthFilter` 를 타지 않습니다. 재발급 요청은 정의상 만료된
access token 을 달고 오기 때문입니다.

---

## 프론트가 401 을 나누는 법

| `errorCode` | 뜻 | 할 일 |
|---|---|---|
| `TOKEN_EXPIRED` | access 만료 | **재발급 시도** (직렬화된 단일 큐로) |
| `INVALID_TOKEN` | 서명 위조·형식 오류·종류 불일치 | 토큰 버리고 로그인 화면 |
| `INVALID_REFRESH_TOKEN` | refresh 만료·위조 | 로그인 화면 |
| `REFRESH_TOKEN_REUSED` | 재사용 탐지로 전 기기 폐기됨 | 로그인 화면 |
| `UNAUTHORIZED` | 토큰 없이 보호된 경로 호출 | 로그인 화면 |

**`TOKEN_EXPIRED` 와 나머지를 반드시 구분하세요.** 뭉뚱그리면 자동 재발급을 붙일
수 없습니다.

### 토큰을 어디에 두나

| | 권장 |
|---|---|
| access | 메모리(상태관리). 새로고침하면 재발급으로 복구 |
| refresh | `localStorage` |

refresh 를 `localStorage` 에 두는 것은 XSS 에 취약합니다. 정석은 httpOnly 쿠키인데,
배포에서 프론트·백 도메인이 갈리면 `SameSite=None; Secure` 가 필요해 HTTPS 가 붙기
전까지 로컬과 배포 동작이 갈립니다. 나중에 쿠키로 바꿔도 `TokenResponse` 형태는
그대로라 프론트 수정은 인터셉터 한 곳입니다.

---

## 로그인을 구현하는 방법

**토큰 코드는 건드리지 않습니다.** 사용자를 확인한 뒤 한 줄만 부르세요.

```java
User user = ...;                       // 이메일 검증 또는 카카오 조회로 찾은 사용자
return Result.ok(tokenService.issue(user));
```

### 이메일

`PasswordEncoder`(BCrypt) 빈이 이미 있습니다. 주입해서 `encode` / `matches` 만
쓰면 됩니다. 비밀번호는 `users` 가 아니라 `user_auth.password` 에 있습니다.

로그인·회원가입에는 **`@RateLimit` 을 반드시 붙이세요.** 안 붙이면 비밀번호
대입 공격이 무제한입니다.

```java
@RateLimit(key = "auth-login", limit = 10, windowSeconds = 60)
```

### 카카오

`OAuthClient` 를 구현하세요. `infrastructure/oauth/KakaoOAuthClient` 자리입니다.

**인가 코드 방식을 씁니다.** 프론트가 받은 `code` 를 백엔드가 토큰으로 교환하고
사용자를 조회합니다. 프론트가 카카오 access token 을 직접 받아 넘기는 방식은 쓰지
마세요 — 클라이언트 시크릿이 프론트로 나가고, 그 토큰이 정말 우리 앱에서 발급된
것인지 검증할 책임이 우리에게 넘어옵니다.

계정 연동 규칙은 [`02-database.md`](./02-database.md) 의 "연동 규칙" 을 그대로
따르세요. **`OAuthUserInfo.linkable()` 이 true 일 때만 기존 계정에 연결합니다.**
`is_email_verified` 확인은 선택이 아닙니다 — 빼면 남의 이메일 주소만 아는 사람이
그 계정에 올라탑니다.

---

## 설정

```yaml
app:
  jwt:
    secret: ${APP_JWT_SECRET:...}   # HS256, 최소 32바이트
    access-token-validity: 30m
    refresh-token-validity: 14d
```

`local` 이 아닌 프로파일에서 개발용 기본 시크릿이 그대로면 **앱이 기동에
실패합니다**(`JwtSecretGuard`). 그 값은 저장소에 공개돼 있어 그대로 배포하면
누구나 토큰을 위조할 수 있습니다.

```bash
openssl rand -base64 48
```

---

## 아직 없는 것

**WebSocket 연결 인증이 없습니다.** `JwtAuthFilter` 는 HTTP 필터인데 브라우저
`WebSocket` API 는 `Authorization` 헤더를 못 보냅니다. 지금은 `sessionId` 만 알면
누구나 붙습니다. 도메인 코드가 없어 push 되는 게 없지만, **면접 API 를 만들 때
반드시 같이 막아야 합니다.** 자세한 것은 이슈 #3.
