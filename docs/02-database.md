# 02. 데이터베이스

PostgreSQL 16. `ddl-auto: update` 로 운영합니다. **`create` 를 쓰지 마세요.**

---

## 사용자

**사람 하나 = `users` 한 행.** 로그인 수단은 몇 개든 `user_auth` 로 붙습니다.

```sql
users
user_id       VARCHAR(50)   PK          -- UUID 문자열
email         VARCHAR(255)  UNIQUE NULL -- 카카오 이메일 미동의 시 null
nickname      VARCHAR(50)   NOT NULL
created_at    TIMESTAMP     NOT NULL
```

```sql
user_auth
auth_id       VARCHAR(50)   PK
user_id       VARCHAR(50)   NOT NULL FK -> users(user_id)
provider      VARCHAR(20)   NOT NULL    -- LOCAL | KAKAO
provider_id   VARCHAR(100)  NULL        -- 카카오 회원번호. LOCAL 은 null
password      VARCHAR(255)  NULL        -- LOCAL 만 채움(BCrypt). 소셜은 null
created_at    TIMESTAMP     NOT NULL

UNIQUE (provider, provider_id)          -- 같은 카카오 계정이 두 번 붙지 않게
UNIQUE (user_id, provider)              -- 한 사람이 같은 제공자를 두 번 걸지 않게
```

**테이블명이 `user` 가 아니라 `users` 입니다.** `user` 는 PostgreSQL 예약어라
`create table user` 가 문법 오류로 죽습니다. 매번 큰따옴표로 감싸는 것보다 이름을
바꾸는 편이 낫습니다. `@Table(name = "users")` 로 두세요.

`email` 이 nullable 인 것이 중요합니다. **카카오 이메일은 선택 동의라 안 줄 수
있습니다.** PostgreSQL 의 UNIQUE 는 NULL 중복을 허용하므로 제약은 그대로 둡니다.

`password` 는 `users` 가 아니라 `user_auth` 에 있습니다. 소셜 전용 계정은
비밀번호가 없고, 한 사람이 이메일과 카카오를 동시에 쓸 수 있기 때문입니다.

### 왜 인증 수단을 분리했나

이메일로 가입한 사람이 나중에 카카오로 로그인했을 때 **별개 계정을 만들지 않고
기존 계정에 연결**하기 위해서입니다. 한 테이블에 `provider` 컬럼만 두면 같은
사람의 면접 기록이 두 계정으로 쪼개집니다.

```
users(user_id=u_1, email=kim@example.com, nickname=김취준)
  ├─ user_auth(provider=LOCAL, password=$2a$...)
  └─ user_auth(provider=KAKAO, provider_id=3821049182)
```

### 연동 규칙

**두 방향의 허용 여부가 다릅니다. 헷갈리기 쉬우니 주의하세요.**

| 상황 | 동작 |
|---|---|
| 카카오 로그인 → 같은 이메일의 계정이 이미 있음 | **연결한다.** `user_auth(KAKAO)` 행만 추가 |
| 이메일 회원가입 → 같은 이메일의 계정이 이미 있음 | **거부한다.** `EMAIL_ALREADY_EXISTS` |
| 카카오가 이메일을 안 줬거나 미검증 | 연결하지 않고 **새 `users` 행 생성** (`email = null`) |

**왜 한쪽만 막나요.** 카카오는 이메일 소유를 검증하고 그 결과를
`kakao_account.is_email_verified` 로 알려줍니다. 검증된 이메일이면 "이 사람이 그
이메일의 주인"이 보장되므로 연결해도 안전합니다.

반대로 **이메일 회원가입은 이메일 소유를 증명하지 않습니다.** 이 방향을 열어두면
남의 이메일 주소만 아는 사람이 그 주소로 가입해 비밀번호를 걸고 남의 카카오
계정에 올라탈 수 있습니다. 이메일 인증 메일 기능이 생기기 전까지는 막습니다.

연결은 `is_email_verified` 가 **true 일 때만** 합니다. 카카오가 이메일을 줘도
미검증이면 새 계정을 만듭니다.

### 카카오가 안 주는 값

닉네임도 선택 동의입니다. 못 받으면 `"면접자" + user_id 앞 6자` 로 채웁니다.
`nickname` 은 화면에 항상 떠야 해서 NOT NULL 을 유지합니다.

---

## 문서 (이력서 · 포트폴리오)

```sql
document_id   VARCHAR(50)   PK
user_id       VARCHAR(50)   NOT NULL FK
type          VARCHAR(20)   NOT NULL   -- RESUME | PORTFOLIO
file_name     VARCHAR(255)  NOT NULL
object_key    TEXT          NOT NULL   -- S3 키
mime_type     VARCHAR(100)  NOT NULL
file_size     BIGINT        NOT NULL
created_at    TIMESTAMP     NOT NULL
```

**S3 URL을 저장하지 않습니다.** `object_key` 만 두고 필요할 때 Presigned URL을
만듭니다. Presigned URL은 만료되고, 버킷·경로가 바뀌면 전부 손봐야 합니다.

---

## 면접 세션

```sql
session_id           VARCHAR(50)   PK        -- AI가 발급
user_id              VARCHAR(50)   NOT NULL FK
resume_id            VARCHAR(50)   NOT NULL FK
company_id           VARCHAR(50)   NULL      -- 회사 미선택 연습 모드
persona              VARCHAR(20)   NOT NULL  -- friendly | pressure
job_role             VARCHAR(100)  NOT NULL
question_count       INT           NOT NULL  -- 3 | 6 | 9
retry_of_session_id  VARCHAR(50)   NULL      -- ★ 항상 최초 세션 ID
status               VARCHAR(20)   NOT NULL  -- in_progress | completed | aborted
created_at           TIMESTAMP     NOT NULL
```

`session_id` 는 AI 서버가 발급한 값을 그대로 씁니다.

`company_id` 가 NULL 허용인 것이 중요합니다. 회사를 고르지 않고 연습만 하는
사용자가 있습니다.

`retry_of_session_id` 는 **직전 회차가 아니라 항상 최초 세션**을 가리킵니다.
자세한 내용은 [`12-replay.md`](./12-replay.md).

---

## 질문 · 답변 로그

```sql
session_id        VARCHAR(50)   -- PK (복합)
question_id       VARCHAR(50)   -- PK (복합)
type              VARCHAR(20)   NOT NULL  -- question | followup | reask
text              TEXT          NOT NULL
audio_url         TEXT          NULL      -- TTS_FAILED 시 null
category          VARCHAR(20)   NULL      -- reask는 null
difficulty        VARCHAR(5)    NULL      -- reask는 null
is_spare_topic    BOOLEAN       NOT NULL
is_replay         BOOLEAN       NOT NULL
question_number   INT           NOT NULL
topic_index       INT           NOT NULL
answer_audio_url  TEXT          NULL
created_at        TIMESTAMP     NOT NULL
```

### ★ 복합 PK

`question_id` 는 `"q_1"`, `"q_4r"` 처럼 **세션 스코프**입니다.
다른 세션과 충돌하므로 `(session_id, question_id)` 복합키로 잡습니다.

```java
@Entity
@IdClass(QuestionLogId.class)
public class QuestionLog {
    @Id private String sessionId;
    @Id private String questionId;
    ...
}
```

### ★ category 는 enum으로 만들지 않습니다

AI가 정의한 8개 값이며, 가운뎃점(·)까지 정확히 일치해야 합니다.

```
지원동기  직무역량  프로젝트경험  문제해결
협업·갈등  실패·성장  가치관·인성  미래계획
```

재연습 요청 시 값이 다르면 AI가 `INVALID_CATEGORY` 를 반환합니다.

**AI가 준 문자열을 그대로 저장하고 그대로 되돌려줍니다.** Java enum으로 매핑하지
않는 이유는 두 가지입니다. 한글과 가운뎃점 때문에 변환 과정에서 깨지기 쉽고,
AI가 카테고리를 추가할 때 백엔드 배포 없이 대응할 수 없게 됩니다.

검증이 필요하면 저장 시점에 목록과 대조만 하고 원문을 보관합니다.

```java
// 나쁨
public enum Category { 지원동기, 직무역량, ... }

// 좋음
private String category;   // AI 원문 그대로
```

### is_spare_topic 과 is_replay

이름이 비슷하지만 가리키는 축이 다릅니다.

| 필드 | 의미 | 용도 |
|---|---|---|
| `is_spare_topic` | 계획된 토픽인가, 채우기용 토픽인가 | 세션 내부 구조. 통계·디버깅 |
| `is_replay` | 1회차와 동일한 질문인가 | 회차 간 관계. **비교 화면은 이것만** |

둘 다 모든 응답에 항상 포함되므로 `NOT NULL` 로 둡니다.
`reask` 는 둘 다 항상 false입니다.

### difficulty

`L1` ~ `L3`. `reask` 에서는 null입니다.

재연습 시 꼬리질문의 난이도도 1회차와 같아야 하므로 반드시 저장합니다.

---

## 리포트

리포트 계약 확정 후 작성합니다. [`13-report.md`](./13-report.md) 참고.

---

## 인덱스

```sql
CREATE INDEX idx_session_user       ON interview_session(user_id, created_at DESC);
CREATE INDEX idx_session_retry_root ON interview_session(retry_of_session_id);
CREATE INDEX idx_qlog_session       ON question_log(session_id, question_number);
CREATE INDEX idx_document_user      ON document(user_id, type);
```

`idx_session_retry_root` 는 회차 목록 조회(같은 루트를 가진 세션 전부)에 씁니다.

`user_auth` 는 인덱스를 따로 만들지 않습니다. 로그인 조회에 쓰는
`(provider, provider_id)` 와 `(user_id, provider)` 는 UNIQUE 제약이 이미 인덱스를
만듭니다. 같은 걸 두 번 만들면 쓰기만 느려집니다.
