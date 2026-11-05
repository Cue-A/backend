# 사용자 마이페이지 API (프로필 · 계정)

> 관련 이슈: 없음 (아직 이슈 생성 전 — 설계 논의 단계)
> 작성일: 2026-11-02

## 배경 / 목표

마이페이지에서 쓸 사용자 도메인 API를 추가한다. 원 요구사항은 Notion 표로 받은 7개 행이다.

```
내정보 조회     GET    /api/users/me/profile     ← 프로필 수정의 짝 (아래 참고, 이름과 경로가 헷갈림)
프로필 수정     PATCH  /api/users/me/profile
계정 정보 조회  GET    /api/users/me/account
계정 설정(수정) PATCH  /api/users/me/email
~~명함 생성     POST   /api/users/me/business-card~~    (2026-11-05: 범위 제외 결정)
~~명함 조회     GET    /api/users/me/business-card~~
~~명함 수정     PATCH  /api/users/me/business-card~~
```

> **2026-11-05 결정: 명함(섹션 5~7) 범위에서 완전히 제외.** 더 이상 진행하지 않는다.
> 새 테이블(`business_card`)·마이그레이션·엔티티·API 모두 만들지 않는다. 이 문서에
> 남아 있는 명함 관련 설계(아래 섹션 5~7)는 과거 기록용으로만 남기고 "구현 계획"
> 체크리스트에서 제거했다.

> **헷갈리기 쉬운 지점:** Notion 표의 "내정보 조회"는 이미 구현된 `GET /api/users/me`
> (로그인 3개 API가 공유하는 `UserResponse`)가 **아니다.** 이름이 같아서 처음에 그걸로
> 잘못 매칭했었다. 실제로는 **프로필 수정(PATCH)의 짝이 되는 조회 API**를 가리킨다 —
> 즉 `GET /api/users/me/profile`. 기존 `GET /api/users/me`는 이 작업과 완전히 별개이고
> 그대로 둔다.

지금까지 채팅으로 설계를 다듬는 과정에서 범위가 여러 번 바뀌었다 — 이 문서는 그 결과를
다음 세션이 그대로 이어받을 수 있게 기록해둔 것이다. **아직 코드는 한 줄도 없다.**
회원가입·로그인·탈퇴(PR #64, 리뷰 반영 완료·머지 대기)만 구현돼 있다.

완료 기준: 아래 "결정 필요 사항"이 전부 확정되고, 7개 행의 API 명세서가 최종본으로
정리되면 끝. (이 문서는 설계 정리이지 구현 계획이 아니다 — 구현은 명세 확정 후 별도로 진행한다.)

## 범위

- 포함: 프로필 조회(`GET /me/profile`, Notion 표의 "내정보 조회")/수정, 프로필 사진 업로드,
  계정 정보 조회, 계정 설정(이메일 변경 — #121 구현 완료, 비밀번호 변경)
- 제외: `GET /api/users/me`(이미 구현된 로그인 공용 `UserResponse`) 변경 — Notion 표의
  "내정보 조회"와 이름이 같아 헷갈리지만 **서로 다른 엔드포인트**다. 아래 "확정된 것" 3번 참고
- 제외: DB 마이그레이션 — 전부 기존 컬럼 재사용으로 최종 결정
- 제외: 명함 (2026-11-05 결정, 위 참고), 알림 설정(`notification_setting`) — 이번 범위 아님

## 영향 범위 / 관련 도메인

`domain/user` 하나다. 기존 코드:

- `UserController` — `GET /me`, `DELETE /me`만 있음. 여기에 메서드 추가.
- `UserResponse` — `userId`, `nickname`, `email`, `providers`. **이 모양은 안 바꾼다** (아래 참고).
- `UserProfile` 엔티티 — `profileImageUrl`, `introduction`, `targetJobRole`, `careerLevel`,
  `schoolName`, `major`, `graduationYear` 전부 nullable. 전부 이미 있고 저장 전엔 행 자체가 없을 수 있음.
- `UserAuth` 엔티티 — provider별 로그인 수단. `password`는 LOCAL만 값이 있음(BCrypt 해시).
  계정 정보 조회·계정 설정이 참조한다.
- `docs/03-auth.md` — 로그인 세 경로(`signup`/`login`/`oauth/kakao`)가 전부 `TokenResponse.user`에
  `UserResponse`를 그대로 싣는다. 이 계약을 건드리면 세 API가 전부 영향받는다.

## API 변경

### 응답 포맷 — `Result<T>` 그대로

```json
{ "success": true,  "data": {...}, "errorCode": null,        "message": null }
{ "success": false, "data": null,  "errorCode": "INVALID_REQUEST", "message": "..." }
```

이번 설계 중 초안에 `code`(→`errorCode`가 맞음), 성공 시 안내 문장(→ 항상 `null`),
필드별 `errors` 배열(→ 지금 없음, 검증 실패는 필드 에러를 콤마로 이어붙인 문자열 하나를
`INVALID_REQUEST`에 담아 반환 — `GlobalExceptionHandler.handleValidation` 확인함)이
전부 실제 컨벤션과 달라서 맞춰놨다. `errors` 배열 형태를 정말 쓰고 싶으면 이 API 하나가
아니라 `GlobalExceptionHandler` 전체를 바꾸는 별도 결정이 필요하다.

### 에러 코드 — 기존 `ErrorCode` enum 재사용

새 enum 값을 추가하지 않고 기존 걸 쓴다.

| 상황 | 코드 |
|---|---|
| 인증 안 됨 / 토큰 위조 / 토큰 만료 | `UNAUTHORIZED` / `INVALID_TOKEN` / `TOKEN_EXPIRED` |
| 요청 형식·검증 오류 (빈 요청 포함, 메시지로 구분) | `INVALID_REQUEST` |
| 사용자 없음 | `USER_NOT_FOUND` |
| 이메일 중복 | `EMAIL_ALREADY_EXISTS` |
| 비밀번호 재확인 불일치 | `INVALID_CREDENTIALS` |
| 서버 오류 | `INTERNAL_ERROR` (`INTERNAL_SERVER_ERROR` 아님) |

"수정할 필드가 없음" 같은 케이스는 새 enum 값 대신
`Result.fail(ErrorCode.INVALID_REQUEST, "수정할 프로필 정보가 없습니다")` 오버로드로 메시지만 바꿔 쓴다.

### 0. (참고, 변경 없음) 기존 `GET /api/users/me`

Notion 표의 "내정보 조회"와 이름이 같지만 다른 엔드포인트다. 로그인 3개 API(`TokenResponse.user`)가
공유하는 계약이라 이번 작업에서 건드리지 않는다.

```json
{ "userId": "...", "nickname": "홍길동", "email": "user@example.com", "providers": ["LOCAL","KAKAO"] }
```

### 1~2. 프로필 조회 / 수정 (Notion 표 "내정보 조회" + "프로필 수정") — `GET`, `PATCH /api/users/me/profile`

> **2026-11-03 갱신:** 사용자가 노션 명세 원본을 공유해 이전 "3개 필드로 축소" 결정을
> 뒤집었다. **전체 필드 포함으로 확정.** 아래가 최종이다.

```json
// GET 응답 (노션 명세 원본)
{
  "userId": "550e8400-e29b-41d4-a716-446655440000", // users.user_id. 이미 UUID라 별도
                                                      // public_id 컬럼 불필요 (User.java 확인함)
  "userName": "홍길동",                 // users.nickname, 수정 가능
  "email": "user@example.com",          // users.email, 읽기 전용 — PATCH 바디로 안 받음
  "profileImageUrl": "https://...",     // user_profile.profile_image_url, 읽기 전용 — 전용 업로드 API(아래)로만 바뀜
  "introduction": "...",                // user_profile.introduction, 수정 가능
  "targetJobRole": "백엔드 개발자",      // user_profile.target_job_role, 수정 가능
  "careerLevel": "entry",               // user_profile.career_level, 수정 가능
  "schoolName": "한국대학교",            // user_profile.school_name, 수정 가능
  "major": "컴퓨터공학",                // user_profile.major, 수정 가능
  "graduationYear": 2026                // user_profile.graduation_year, 수정 가능
}
// user_profile 행이 아직 없으면 선택 프로필 필드(profileImageUrl 이하)는 전부 null

// PATCH 요청 바디 (부분 수정, 보낸 필드만 반영) — email·userId·profileImageUrl 제외 수정 가능
{ "userName": "...", "introduction": "...", "targetJobRole": "...", ... }
```

- 이메일은 여기서 **표시만** 한다. 실제 변경은 계정 설정(4번)에서만 가능. 계정 정보 조회(3번)와
  값이 겹치지만 **의도된 중복으로 확정** — 수정 권한은 계정 설정에만 있고, 프로필에선 보여주기만
  한다.
- `user_profile` 행이 없는 사용자(가입 후 한 번도 프로필을 저장한 적 없음)의 PATCH 요청은
  이번이 최초 저장이 되어 행을 새로 만든다 (`UserProfile` 엔티티 설계대로).
- `careerLevel`은 노션 명세 예시가 `"entry"`로 소문자 영문 — 기존 프로젝트 규칙상
  `category` 류 문자열 enum 변환 금지 원칙(CLAUDE.md)과 같은 식으로 문자열 그대로
  저장/반환할지, 서버 enum으로 검증할지 확인 필요. 단 DB `career_level`
  (`VARCHAR(20)`, `V1__init.sql:249`)엔 `CHECK` 제약이 없어 **마이그레이션은 불필요** —
  검증은 애플리케이션 레벨에서만 하면 된다.

#### 프로필 사진 업로드 — 서버 통과 (문서 업로드 패턴) *(확정, 2026-11-05 정정)*

> **2026-11-05 결정 (최초 결정 뒤집음):** 처음엔 답변 녹음·영상과 같은 Presigned PUT으로
> 정했으나, 프로필 사진은 **로컬 이미지 파일 업로드만** 되면 된다는 요건이 확인돼
> 뒤집었다. Presigned 는 대용량 파일(세션 녹음·영상)이 서버를 거치는 부담을 피하려는
> 목적인데, 프로필 사진은 보통 수백 KB~2MB 라 그 이유가 적용되지 않는다. 대신 이력서
> 업로드(`POST /api/documents`, 서버가 파일을 직접 받아 S3 에 올림)와 같은 패턴을 쓴다.
> 2단계 발급 플로우·key 소유권 검증 로직이 필요 없어 더 단순하다.

```
POST /api/users/me/profile/photo  (multipart, 로컬 이미지 파일)
→ Spring 이 S3 에 올리고 objectKey/URL 을 user_profile.profile_image_url 에 즉시 반영
→ 응답으로 최종 profileImageUrl 반환
```

- **`PATCH /me/profile` 바디로는 `profileImageUrl` 을 받지 않는다.** 결정 필요 사항
  10번("임의 URL 허용 여부")이 이걸로 해소됨 — 임의 문자열 URL을 그대로 저장하는 경로를
  없애고, 사진은 **이 업로드 API를 거친 결과만** 반영되게 막는다. 프로필 조회 응답의
  `profileImageUrl` 필드 자체는 그대로 유지(값의 출처만 달라짐).
- `DocumentFileStorage`/`S3DocumentFileStorage`/`FileValidator` 패턴 재사용. 이미지
  MIME 화이트리스트(`image/jpeg`, `image/png` 등)와 용량 제한을 `FileValidator` 에
  추가해야 함.
- 신규 object key 규칙 필요: `profiles/{userId}/photo.{ext}` (`ObjectKeys` 에 추가,
  `docs/20-storage.md` 경로 표에도 반영). 기존 사진을 덮어쓰는 구조라 사용자당 파일
  1개 — 재업로드 시 이전 파일을 지울지 같은 key 를 덮어쓸지는 구현 시 결정.
- **탈퇴 시 정리 영향(그대로 유지):** `UserFileCleaner.removeAll` 이 `resumes/{userId}/` 와
  `sessions/{sessionId}/` 만 지우므로, 새 접두사(`profiles/{userId}/`)도 같이 지우도록
  한 줄 추가해야 한다 — 안 하면 탈퇴 후 고아 사진 객체가 남는다. **구현 시 빠뜨리기
  쉬운 지점으로 기록.**

#### 경력 — 단일 레벨(`careerLevel`) 유지, 다중 경력 아님 *(확정, 2026-11-05)*

> **2026-11-05 결정:** "학력·경력도 넣는다"는 요구가 회사·직무·기간을 여러 건 저장하는
> 경력 히스토리로 오해될 수 있어 확인함. **범위는 지금 있는 `careerLevel`
> (`student`/`entry`/`junior`/`experienced`) 그대로다.** 여러 건의 실제 경력
> (회사명·직무·기간)을 저장하는 기능은 **범위 밖** — 필요해지면 `career` 전용 테이블과
> 마이그레이션이 새로 필요하므로 별도 이슈로 다뤄야 한다. 학력(`schoolName`/`major`/
> `graduationYear`)은 원래부터 단일 값이라 이 결정과 무관하게 그대로.

#### PATCH (프로필 수정) — 요청 바디 / 처리 규칙

> **2026-11-03 갱신:** 사용자가 공유한 PATCH 명세 원본을 레포와 대조해 자체 모순
> 2건을 고쳤다 (아래 "고친 것" 참고). 노션 원본 그대로가 아니라 이 수정본이 최종.

- 요청 바디: `userName`/`introduction`/`targetJobRole`/`careerLevel`/
  `schoolName`/`major`/`graduationYear` 전부 선택값, 보낸 필드만 반영. `userId`/`email`은
  요청으로 안 받음(토큰으로 식별, 이메일은 계정 설정에서만). `profileImageUrl`도 이
  바디로 안 받음 — 전용 업로드 API(위 "프로필 사진 업로드" 참고)로만 바뀜.
- `careerLevel` 허용값: `student`/`entry`/`junior`/`experienced`.
- 빈 바디는 거부 (`EMPTY_UPDATE_REQUEST`).
- **고친 것:**
  1. 원본엔 `careerLevel` 검증 실패가 `INVALID_INPUT`의 `errors` 배열과 별도
     top-level `INVALID_CAREER_LEVEL` 두 가지로 중복 정의돼 있었다 → `INVALID_CAREER_LEVEL`
     제거, 다른 필드와 동일하게 `INVALID_INPUT.errors[]`로 통합.
  2. 원본엔 401(토큰 에러) 응답이 전부 빠져 있었다 → GET 명세와 동일한
     `ACCESS_TOKEN_NOT_FOUND`/`INVALID_ACCESS_TOKEN`/`ACCESS_TOKEN_EXPIRED` 3종 추가.
  3. 원본 500 코드가 `PROFILE_UPDATE_FAILED`로 GET 명세의 `INTERNAL_SERVER_ERROR`와
     이름이 달랐다 → `INTERNAL_SERVER_ERROR`로 통일.

#### 응답 포맷 · 에러 코드 — 레포 컨벤션 그대로 *(확정, 2026-11-05)*

노션 명세 원본은 레포의 실제 `Result<T>`/`ErrorCode` 컨벤션과 다르다. **새 포맷을 만들지
않고 기존 `Result<T>`/`ErrorCode`/`GlobalExceptionHandler`를 그대로 쓴다.** `CLAUDE.md`가
"새 API는 `Result<T>`로 감싸 반환합니다"를 이미 항상 지킬 규칙으로 못박아 둬서, 이 API들만
다른 포맷을 쓰는 선택지 자체가 없다 — 기획/프론트와 새로 맞출 축이 아니라 기존 컨벤션을
따르는 것으로 자동 확정. 이메일 변경(#121)도 이 컨벤션으로 이미 구현·머지됐다.

| 항목 | 노션 명세 (참고용, 적용 안 함) | 실제 구현 (`Result.java`, `ErrorCode.java`) |
|---|---|---|
| 응답 키 | `success`/`code`/`message`/`data`, 성공 시에도 안내 문장 | `success`/`data`/`errorCode`/`message`, 성공 시 `message` 항상 `null` |
| 토큰 에러 | `ACCESS_TOKEN_NOT_FOUND` / `INVALID_ACCESS_TOKEN` / `ACCESS_TOKEN_EXPIRED` | `UNAUTHORIZED` / `INVALID_TOKEN` / `TOKEN_EXPIRED` (enum에 이미 있음, 이름만 다름) |
| 서버 오류 | `INTERNAL_SERVER_ERROR` | `INTERNAL_ERROR` (enum에 이미 있음, 이름만 다름) |
| 사용자 없음 | `USER_NOT_FOUND` | `USER_NOT_FOUND` (동일) |
| 검증 실패 세분화 | `INVALID_INPUT`/`EMPTY_UPDATE_REQUEST` 등 필드별 분리 | `INVALID_REQUEST` 하나 + 메시지로 구분 (`GlobalExceptionHandler.handleValidation`) 그대로 유지 |

이 API 하나만 다른 포맷을 쓸 수는 없다 — 바꾸면 `Result<T>`·`GlobalExceptionHandler`·
`ErrorCode` enum 전체에 영향이라 다른 모든 API 응답이 같이 바뀐다. 결정 필요 사항 6번과
동일한 축.

### 3. 계정 정보 조회 — `GET /api/users/me/account` *(확정, 2026-11-03: 노션 댓글 없어 Claude가 엔티티 기준으로 설계, 사용자 확정)*

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "계정 정보 조회에 성공했습니다.",
  "data": {
    "email": "user@example.com",
    "createdAt": "2026-09-01T10:00:00",
    "auths": [
      { "provider": "LOCAL", "hasPassword": true,  "linkedAt": "2026-09-01T10:00:00" },
      { "provider": "KAKAO", "hasPassword": false, "linkedAt": "2026-09-15T14:20:00" }
    ]
  }
}
```

- `createdAt` = `users.created_at`(가입일, 확정). `auths[].linkedAt` = 각 `user_auth.created_at`(해당 수단 등록 시점)으로 역할 분리.
- `password` 원문·해시, `provider_id`(카카오 회원번호)는 절대 응답에 담지 않는다. `hasPassword`만 내려준다.
- 에러: 401(`ACCESS_TOKEN_NOT_FOUND`/`INVALID_ACCESS_TOKEN`/`ACCESS_TOKEN_EXPIRED`), `USER_NOT_FOUND`, `INTERNAL_SERVER_ERROR`.

### 4. 계정 설정 메뉴 — "이메일·비밀번호·로그아웃·탈퇴"

> **2026-11-03 갱신:** 사용자가 원하는 계정 설정 화면 구성이 확정됨: 이메일 변경 /
> 비밀번호 변경 / 로그아웃 / 탈퇴하기 4개. 이 중 **로그아웃·탈퇴는 이미 구현돼 있어**
> 신규 작업이 아니다.

| 기능 | API | 상태 |
|---|---|---|
| 로그아웃 | `POST /api/auth/logout` | 이미 구현됨 (`AuthController.java:83`, `TokenService.logout`) |
| 탈퇴하기 | `DELETE /api/users/me` | 이미 구현됨 (PR #63/#64) |
| 이메일 변경 | `PATCH /api/users/me/email` | 신규 — 아래 4-1 |
| 비밀번호 변경 | `PATCH /api/users/me/password` | 신규 — 아래 4-2 |

#### 4-1. 이메일 변경 — `PATCH /api/users/me/email`

```json
// Request
{ "newEmail": "new@example.com", "currentPassword": "..." }
```

> **2026-11-05 갱신 (결정 뒤집음):** 처음엔 카카오 전용 계정도 비밀번호 확인만 생략하고
> 이메일 변경은 허용하는 쪽으로 정했으나, **카카오 전용 계정은 이메일 변경 자체를 막는
> 쪽으로 뒤집었다.** 이메일이 카카오 쪽 계정 식별·연동(`KakaoAccountLinker`)과 묶여 있어,
> 우리 DB에서만 임의로 바꾸면 다음 카카오 로그인 때 그 계정과 어긋난다. **이메일 변경은
> `LOCAL` 가입 계정만 가능.**

**확정된 처리 규칙 (카카오 전용 계정은 전면 차단):**
- 해당 사용자에 `LOCAL` 인증(`UserAuth`)이 **없으면**(카카오 전용) **전용 에러로 거부**한다 —
  신규 `ErrorCode.EMAIL_CHANGE_NOT_ALLOWED`(400) 필요. `PASSWORD_NOT_SET`(4-2, 비밀번호
  변경용)과 같은 축의 "카카오 전용 계정은 이 설정을 못 건드린다" 패턴.
- `LOCAL` 인증이 있으면 `currentPassword` **필수** — 불일치 시 `INVALID_CREDENTIALS`.
- `newEmail`이 `users.email` UNIQUE에 걸리면 `EMAIL_ALREADY_EXISTS`.
- 성공 시 `users.email`만 갱신. `UserAuth`는 건드리지 않음.

**Response 200:**
```json
{
  "success": true, "code": "SUCCESS", "message": "이메일 변경에 성공했습니다.",
  "data": { "email": "new@example.com" }
}
```

**에러:** `INVALID_REQUEST`(이메일 형식), `INVALID_CREDENTIALS`(비밀번호 불일치),
`EMAIL_ALREADY_EXISTS`, `EMAIL_CHANGE_NOT_ALLOWED`(신규, 카카오 전용 계정), 401 3종,
`USER_NOT_FOUND`, `INTERNAL_SERVER_ERROR`.

#### 4-2. 비밀번호 변경 — `PATCH /api/users/me/password` (신규 API, 기존 명세 없음)

```json
// Request
{ "currentPassword": "...", "newPassword": "..." }
```

**확정된 처리 규칙 (카카오 전용 계정 분기):**
- 해당 사용자에 `LOCAL` 인증이 **없으면**(카카오 전용) **전용 에러로 거부**한다 — "비밀번호 설정" 자체는 범위 밖(별도 기능, `UserAuth` 신규 행 생성 로직 필요)이라 이번엔 안 만든다.
  - 신규 `ErrorCode.PASSWORD_NOT_SET`(400) 필요 — `ErrorCode`는 Java enum일 뿐 DB `CHECK` 제약과 무관해 **마이그레이션 불필요**.
- `LOCAL` 인증이 있으면 `currentPassword` 검증(불일치 시 기존 `INVALID_CREDENTIALS` 재사용) 후 `newPassword` 형식 검증(기존 `INVALID_PASSWORD_FORMAT` 재사용, `AuthService`의 가입 시 규칙과 동일하게).
- 통과하면 `UserAuth.changePassword(passwordEncoder.encode(newPassword))` 호출(기존 메서드 재사용, `UserAuth.java:71`).
- 성공 응답엔 비밀번호 관련 데이터를 아무것도 담지 않는다 (`data: null`).

**Response 200:**
```json
{ "success": true, "code": "SUCCESS", "message": "비밀번호 변경에 성공했습니다.", "data": null }
```

**에러:** `INVALID_REQUEST`, `INVALID_CREDENTIALS`, `INVALID_PASSWORD_FORMAT`, `PASSWORD_NOT_SET`(신규), 401 3종, `USER_NOT_FOUND`, `INTERNAL_SERVER_ERROR`.

### 5~7. (제외, 2026-11-05) 명함 생성·조회·수정 — `POST`/`GET`/`PATCH /api/users/me/business-card`

> **범위에서 완전히 제외됐다. 아래는 과거 설계 기록일 뿐 구현하지 않는다.**

> **2026-11-03 재설계:** 사용자가 실제 UI 화면을 공유해 필드 구성이 전부 바뀌었다
> (`display_name`/`job_role`/`one_liner`/`strengths` 가정 폐기).
>
> 핵심 결정 3개:
> 1. **공개 범위 — 본인만 조회 유지.** 화면의 "공유하기" 버튼은 **프론트 전용**
>    (다운로드한 PNG를 `navigator.share()`로 공유 — 백엔드 관련 없음). 공개 링크
>    엔드포인트는 만들지 않는다.
> 2. **`email` 필드 — 명함 전용 별도 입력값.** `users.email`(계정 이메일)과 무관.
>    사용자가 명함에 쓸 연락용 이메일을 따로 입력한다.
> 3. **`color` — 고정 팔레트 5색.** AI 서버 응답 필드가 아니라 우리가 직접 설계한
>    값이라 CLAUDE.md의 "AI category enum 변환 금지" 규칙과 무관 — Java enum +
>    DB `CHECK`로 정상 구현.
>
> **PNG 다운로드·공유하기는 캔버스 렌더링 + `navigator.share()`로 전부 프론트에서
> 처리 — 백엔드 작업 범위 아님.**

```sql
business_card (1:1, user_id UNIQUE)
  business_card_id  VARCHAR(50)   PK (UUID)
  user_id           VARCHAR(50)   FK → users, UNIQUE, ON DELETE CASCADE
  name              VARCHAR(50)   -- 생성 시 생략하면 users.nickname 기본값
  company_name      VARCHAR(50)   -- 기본값 없음 (계정 쪽 대응 필드 없음)
  job_role          VARCHAR(50)   -- 생성 시 생략하면 user_profile.target_job_role 기본값
  email             VARCHAR(255)  -- 명함 전용, users.email과 무관
  phone_number      VARCHAR(20)
  color             VARCHAR(20)   NOT NULL DEFAULT 'PURPLE'
                     CHECK (color IN ('PURPLE','BLUE','GREEN','ORANGE','BLACK'))
  created_at        TIMESTAMP     NOT NULL
  updated_at        TIMESTAMP     NOT NULL
```

- **생성** `POST /me/business-card` — 이미 있으면 409 `BUSINESS_CARD_ALREADY_EXISTS`(신규
  `ErrorCode`). `name`/`jobRole` 생략 시 각각 `users.nickname`/
  `user_profile.target_job_role` 기본값, `color` 생략 시 `PURPLE` 기본값.
  `companyName`/`email`/`phoneNumber`는 기본값 없이 생략 시 `null`.
- **조회** `GET /me/business-card` — 본인 명함만, 타인 조회 불가. 없으면 404
  `BUSINESS_CARD_NOT_FOUND`(신규 `ErrorCode`).
- **수정** `PATCH /me/business-card` — 부분 수정, 명함 없으면 `BUSINESS_CARD_NOT_FOUND`
  (자동 생성 안 함, 생성이 선행되어야 함). 빈 바디는 `EMPTY_UPDATE_REQUEST`(프로필 수정과
  동일 코드 재사용).
- 신규 `ErrorCode` 2개(`BUSINESS_CARD_ALREADY_EXISTS`, `BUSINESS_CARD_NOT_FOUND`)는
  Java enum일 뿐 DB `CHECK` 제약과 무관해 마이그레이션 영향 없음 — `business_card` 테이블
  자체의 `V2__add_business_card.sql`만 필요.

## DB 변경

**마이그레이션 없음.** 프로필이 기존 `user_profile` 컬럼만 쓰고, 명함이 범위에서
빠지면서(2026-11-05) 새 테이블이 필요한 작업이 없다.

아래는 이번 설계와 별개로 DB 분석 중 발견한 것으로, 별도 판단이 필요하다.

1. **`user_auth` CASCADE 누락** (이번 설계와 별개로 DB 분석 중 발견) —
   `V1__init.sql`에서 `users`를 참조하는 FK 9개(`document`, `folder`, `goal`, `session`,
   `streak`, `user_badge`, `user_company_interest`, `notification_setting`, `user_profile`,
   `bookmarked_question`, `calendar_event`)는 전부 `on delete cascade`가 있는데
   **`fk_user_auth_user_id`만 없다.** `docs/02-database.md`(762줄)와 `CLAUDE.md`는
   "users 삭제 → 종속 테이블 전부 CASCADE"라고 명시한다. 지금은 `User.auths`의 JPA
   cascade(`CascadeType.ALL`, `orphanRemoval=true`)가 대신 처리해서 겉으로는 안 깨지지만,
   JPA를 거치지 않는 삭제 경로(raw SQL 등)에서는 FK 위반으로 실패한다. 이번 작업과
   무관하지만 발견한 김에 기록. 고칠지, 고친다면 이번 PR에 끼워 넣을지 별도 이슈로
   뺄지 결정 필요.

## 구현 계획

명함(섹션 5~7)은 2026-11-05 범위 제외. 남은 항목을 이슈 단위로 나눠 진행한다.

1. [x] 이메일 변경 — `PATCH /api/users/me/email` (#121, 2026-11-05 구현. `UserEmailService`,
   `ErrorCode.EMAIL_CHANGE_NOT_ALLOWED` 추가. 비밀번호 재확인·중복 이메일 검사 포함,
   `UserEmailServiceTest` 6케이스로 검증)
2. [ ] 계정 정보 조회 — `GET /api/users/me/account`
3. [ ] 비밀번호 변경 — `PATCH /api/users/me/password`, `ErrorCode.PASSWORD_NOT_SET` 추가
4. [ ] 프로필 조회/수정 — `GET`/`PATCH /api/users/me/profile`, 요청·응답 DTO, 서비스, 컨트롤러,
   `UserProfileRepository` 신규
4-1. [ ] 프로필 사진 업로드 — `POST /api/users/me/profile/photo` (multipart), `ObjectKeys.profilePhoto`
   추가, 탈퇴 시 `UserFileCleaner.removeAll`에 `profiles/{userId}/` 정리 추가
5. [ ] (별도 판단) `user_auth` CASCADE 마이그레이션
- 로그아웃(`POST /api/auth/logout`)·탈퇴(`DELETE /api/users/me`)는 이미 구현돼 있어
  계정 설정 메뉴에 그대로 연결만 하면 됨 — 신규 작업 아님.

## 결정 필요 사항 / 리스크

~~1. 필드 범위~~ — **확정(2026-11-03 갱신): 노션 명세 전체 필드 포함.** 3개로 축소했던
   이전 결정은 뒤집힘. `userId`(UUID)/`userName`/`email`(읽기전용)/`profileImageUrl`/
   `introduction`/`targetJobRole`/`careerLevel`/`schoolName`/`major`/`graduationYear`.
~~2. 프로필·계정 정보 조회의 이메일 중복~~ — **확정: 의도된 중복으로 유지.** 프로필엔
   표시만(읽기 전용), 수정은 계정 설정에서만.
~~7. 응답 포맷 / 에러 코드명~~ — **확정(2026-11-05): 레포 컨벤션(`Result<T>`/`ErrorCode`)
   그대로. 노션 명세 포맷은 적용 안 함.** 위 "응답 포맷 · 에러 코드" 섹션 참고.
8. **`careerLevel` 값 형식** — **확정(2026-11-05): 애플리케이션 레벨에서 4개 값
   (`student`/`entry`/`junior`/`experienced`)으로 검증, DB엔 평문 문자열로 저장.**
   DB `career_level` 컬럼에 `CHECK` 제약이 없어(마이그레이션 불필요 원칙과 일치)
   서버 검증만으로 충분하다. 허용되지 않은 값은 `INVALID_REQUEST`.
~~9. 검증 실패 코드 세분화 여부~~ — 7번과 동일 축, 같이 확정. 필드 에러는 지금처럼
   콤마로 이어붙인 문자열 하나를 `INVALID_REQUEST`에 담아 반환한다.
~~10. `profileImageUrl` 임의 URL 허용 여부~~ — **확정(2026-11-05): 허용 안 함.**
    `PATCH /me/profile` 바디에서 이 필드를 아예 제거하고, 전용 업로드 API
    (`POST /me/profile/photo`, 로컬 이미지 파일만)를 거친 결과로만 바뀌도록 확정.
    "프로필 사진 업로드" 섹션 참고.

~~2. 계정 설정(이메일 변경) 비밀번호 재확인~~ — **확정(2026-11-05 갱신): LOCAL 계정은
   `currentPassword` 필수. 카카오 전용 계정은 "생략"이 아니라 이메일 변경 자체를
   `EMAIL_CHANGE_NOT_ALLOWED`로 전면 차단.** 섹션 4-1 참고.
~~3. 계정 정보 조회 응답 범위~~ — **확정(2026-11-03): Notion 댓글 끝내 못 받아 Claude가
   엔티티 기준으로 설계, 사용자 확정.** 섹션 3 참고.
11. **비밀번호 변경 API 신규 추가** (신규, 2026-11-03) — 계정 설정 메뉴에 "비밀번호
    변경"이 있어야 해서 `PATCH /api/users/me/password` 신규 추가, 섹션 4-2 참고.
    카카오 전용 계정은 전용 에러(`PASSWORD_NOT_SET`, 신규 `ErrorCode` 값, 마이그레이션
    불필요)로 거부하기로 확정. "비밀번호 설정"(OAuth 계정에 LOCAL 인증 추가) 자체는
    범위 밖.
~~4. 명함 기능 자체~~ — **뒤집힘(2026-11-05): 범위에서 완전히 제외.** 2026-11-03에 확정했던
   설계(본인만 조회, `strengths` 쉼표 구분 문자열, `V2__add_business_card.sql`)는 더 이상
   진행하지 않는다.
5. **`user_auth` CASCADE 누락** — 고칠지, 이번 작업에 포함할지 별도 이슈로 뺄지.
~~6. 검증 실패 응답을 필드별 배열로 바꿀지~~ — **확정(2026-11-05): 안 바꾼다.** 위
   "응답 포맷 · 에러 코드" 섹션 참고.
