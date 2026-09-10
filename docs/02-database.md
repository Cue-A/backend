# 02. 데이터베이스

PostgreSQL 16. `ddl-auto: update` 로 운영합니다. **`create` 를 쓰지 마세요.**

---

## 식별자 규칙

**내부 조인은 `BIGINT` PK, 외부 노출은 `public_id`(UUID) 입니다.**
연속된 정수를 URL 이나 응답에 그대로 내보내면 남의 데이터 개수와 순서가 드러납니다.

예외가 셋 있습니다.

| 테이블 | 예외 | 이유 |
|---|---|---|
| `users` · `user_auth` | PK 가 `VARCHAR(50)` UUID 문자열 | 먼저 만들어진 테이블. 그래서 **모든 `user_id` FK 가 `VARCHAR(50)`** 입니다 |
| `session` · `question` | PK 가 AI 발급 문자열 | AI 서버가 준 ID 를 그대로 씁니다. 아래 참고 |
| `company` · `badge` | `public_id` 없음 | 모두가 같은 목록을 보는 공용 마스터라 ID 를 노출해도 새는 정보가 없습니다 |

시간 컬럼은 `TIMESTAMPTZ` 입니다. 단 먼저 만들어진 `users` · `user_auth` 만
`TIMESTAMP`(타임존 없음)입니다.

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

## 문서 (포트폴리오 · 발표자료 · 대본)

```sql
document
doc_id        BIGINT        PK
public_id     UUID          UNIQUE NOT NULL
user_id       VARCHAR(50)   NOT NULL FK -> users(user_id)
doc_title     VARCHAR(100)  NOT NULL
doc_type      VARCHAR(20)   NOT NULL   -- PORTFOLIO | PRESENTATION | SCRIPT
source_type   VARCHAR(10)   NOT NULL   -- FILE | MARKDOWN
object_key    TEXT          NULL       -- S3 키. FILE 만
file_name     VARCHAR(255)  NULL       -- FILE 만
mime_type     VARCHAR(100)  NULL       -- FILE 만
file_size     BIGINT        NULL       -- FILE 만
file_format   VARCHAR(10)   NULL       -- PDF | DOCX | TXT | PPTX. FILE 만
doc_text      TEXT          NULL       -- MARKDOWN 만
ai_doc_ref    VARCHAR(100)  NULL       -- AI RAG 인덱스 참조
status        VARCHAR(20)   NOT NULL   -- UPLOADED | PARSING | READY | FAILED
created_at    TIMESTAMPTZ   NOT NULL
updated_at    TIMESTAMPTZ   NOT NULL
```

**`source_type` 이 어느 컬럼을 읽을지를 정합니다.** `FILE` 이면 `object_key` 계열,
`MARKDOWN` 이면 `doc_text` 입니다. 양쪽이 전부 nullable 인 이유가 이것입니다.

**S3 URL을 저장하지 않습니다.** `object_key` 만 두고 필요할 때 Presigned URL을
만듭니다. Presigned URL은 만료되고, 버킷·경로가 바뀌면 전부 손봐야 합니다.

`status` 가 `READY` 가 되기 전에는 세션을 시작할 수 없습니다. AI 서버가 문서를
읽을 수 없는 상태입니다.

`ai_doc_ref` 는 AI 가 준 문자열을 보관만 합니다. **벡터 저장소는 AI 파트 소유이며
Spring 은 읽지도 쓰지도 않습니다.** JPA 엔티티를 만들지 마세요.

---

## 폴더

```sql
folder
folder_id     BIGINT        PK
public_id     UUID          UNIQUE NOT NULL
user_id       VARCHAR(50)   NOT NULL FK -> users(user_id)
folder_name   VARCHAR(50)   NOT NULL
created_at    TIMESTAMPTZ   NOT NULL
```

**문서가 아니라 세션을 담습니다.** 이름만 보면 문서 폴더로 오해하기 쉬운데
`document` 에는 `folder_id` 가 없고 `session` 에만 있습니다.

폴더를 지워도 세션은 지우지 않습니다. `session.folder_id` 만 null 이 되어
"폴더 없음"으로 남습니다. 연습 기록이 폴더 정리 한 번에 날아가면 안 됩니다.

---

## 면접 세션

```sql
session
session_id           VARCHAR(50)   PK        -- ★ AI가 발급
user_id              VARCHAR(50)   NOT NULL FK -> users(user_id)
company_id           BIGINT        NULL FK   -- 회사 미선택 연습 모드
document_id          BIGINT        NULL FK   -- 문서 없이 직무만으로 보는 세션
folder_id            BIGINT        NULL FK   -- 폴더에 넣지 않은 세션
retry_of_session_id  VARCHAR(50)   NULL FK   -- ★ 항상 최초 세션 ID
mode                 VARCHAR(20)   NOT NULL
job_role             VARCHAR(50)   NOT NULL
custom_talent        TEXT          NULL      -- 인재상 직접 입력
question_count       INT           NOT NULL  -- 3 | 6 | 9. 기본 6
pressure_level       VARCHAR(10)   NOT NULL
hide_question_text   BOOLEAN       NOT NULL  -- 음성만 듣는 실전 모드
duration_limit       INT           NULL      -- 답변 제한 시간(초)
status               VARCHAR(20)   NOT NULL  -- IN_PROGRESS | COMPLETED | ABORTED
created_at           TIMESTAMPTZ   NOT NULL
updated_at           TIMESTAMPTZ   NOT NULL
completed_at         TIMESTAMPTZ   NULL
```

### ★ PK 를 우리가 만들지 않습니다

`session_id` 는 **AI 서버가 발급한 값을 그대로** 씁니다. `@GeneratedValue` 가
없으므로 저장 전에 반드시 AI 응답의 `session_id` 를 넣어야 합니다.

이 테이블만 `BIGINT` + `public_id` 규칙에서 빠지는 이유입니다. 자동증가 PK 로
잡으면 AI 가 준 세션 ID 를 둘 컬럼이 없어집니다. 이 값 자체가 연속되지 않는
외부 노출 식별자이므로 `public_id` 도 따로 두지 않습니다.

### 나머지

`company_id` 가 NULL 허용인 것이 중요합니다. 회사를 고르지 않고 연습만 하는
사용자가 있습니다. `document_id`, `folder_id` 도 같은 이유로 NULL 허용입니다.

`retry_of_session_id` 는 **직전 회차가 아니라 항상 최초 세션**을 가리킵니다.
3회차에서 이 값은 2회차가 아니라 1회차입니다. 회차 목록은 "같은 루트를 가진
세션 전부"로 뽑으며 직전 회차로 채우면 목록 조회가 재귀 쿼리가 됩니다.
자세한 내용은 [`12-replay.md`](./12-replay.md).

`mode`, `pressure_level` 은 값 목록이 확정되지 않아 자바에서도 `String` 입니다.
정해지면 enum 으로 올리세요.

`folder` 를 지우면 `folder_id` 가, 세션을 지우면 `retry_of_session_id` 가
`SET NULL` 됩니다.

---

## 질문 · 답변 로그

질문과 그 녹음은 `question`, AI 분석 결과는 `answer` 로 나눠 담습니다.

```sql
question
session_id        VARCHAR(50)   -- PK (복합), FK -> session(session_id)
question_id       VARCHAR(50)   -- PK (복합). AI 발급
type              VARCHAR(20)   NOT NULL  -- QUESTION | FOLLOWUP | REASK
text              TEXT          NOT NULL
audio_url         TEXT          NULL      -- TTS_FAILED 시 null
category          VARCHAR(20)   NULL      -- REASK는 null
difficulty        VARCHAR(5)    NULL      -- REASK는 null
is_spare_topic    BOOLEAN       NOT NULL
is_replay         BOOLEAN       NOT NULL
question_number   INT           NOT NULL  -- REASK 에서는 안 올라감
topic_index       INT           NOT NULL
answer_audio_url  TEXT          NULL      -- 사용자 녹음. 업로드 완료 시 채움
is_bookmarked     BOOLEAN       NOT NULL
created_at        TIMESTAMPTZ   NOT NULL
```

```sql
answer
answer_id     BIGINT        PK
session_id    VARCHAR(50)   NOT NULL  -- FK -> question (복합)
question_id   VARCHAR(50)   NOT NULL  -- FK -> question (복합)
answer_text   TEXT          NULL      -- STT 실패 시 null
score         INT           NULL
answer_start  DOUBLE PRECISION NULL   -- 발화 시작 지점(초)
answer_end    DOUBLE PRECISION NULL
think_time    DOUBLE PRECISION NULL   -- 말을 시작하기까지 걸린 시간(초)
answered_at   TIMESTAMPTZ   NULL
metrics       JSONB         NULL      -- 음성·시선 분석 원본
created_at    TIMESTAMPTZ   NOT NULL

UNIQUE (session_id, question_id)
```

### ★ 복합 PK

`question_id` 는 `"q_1"`, `"q_4r"` 처럼 **세션 스코프**입니다.
다른 세션과 충돌하므로 `(session_id, question_id)` 복합키로 잡습니다.

```java
@Entity
@IdClass(QuestionId.class)
public class Question {
    @Id private String sessionId;
    @Id private String questionId;
    ...
}
```

`answer` 와 `bookmarked_question` 은 이 두 컬럼을 묶어 FK 로 겁니다.

### ★ question_number 에 UNIQUE 를 걸지 마세요

되묻기에서는 올라가지 않습니다. 같은 `question_number` 를 가진 행이 한 세션에
둘 이상 존재하는 것이 정상이며, UNIQUE 를 걸면 되묻기 저장이 실패합니다.

### ★ 녹음은 question, 분석 결과는 answer

`answer_audio_url` 이 `answer` 가 아니라 `question` 에 있습니다. **업로드가 끝난
시점에는 아직 AI 분석 전이라 `answer` 행이 없습니다.** 답변하지 않고 끝난
질문에도 `answer` 행이 없으므로, 문항 수를 셀 때 어느 테이블 기준인지
구분하세요.

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

`type` 은 반대로 enum 입니다. 값이 3개로 고정된 백엔드 개념이고 한글이 아닙니다.

### is_spare_topic 과 is_replay

이름이 비슷하지만 가리키는 축이 다릅니다.

| 필드 | 의미 | 용도 |
|---|---|---|
| `is_spare_topic` | 계획된 토픽인가, 채우기용 토픽인가 | 세션 내부 구조. 통계·디버깅 |
| `is_replay` | 1회차와 동일한 질문인가 | 회차 간 관계. **비교 화면은 이것만** |

둘 다 모든 응답에 항상 포함되므로 `NOT NULL` 로 둡니다.
`REASK` 는 둘 다 항상 false입니다.

### difficulty

`L1` ~ `L3`. `REASK` 에서는 null입니다.

재연습 시 꼬리질문의 난이도도 1회차와 같아야 하므로 반드시 저장합니다.

---

## 리포트

> ⚠️ **계약 확정 전 상태입니다.** [`90-open-questions.md`](./90-open-questions.md) 는
> 계약 도착 전까지 만들지 말라고 하지만, ERD 초안에 테이블이 있어 먼저 잡아뒀습니다.
> 점수 스케일조차 미확정이라 **계약이 오면 갈아엎을 수 있습니다.**

```sql
report
report_id         BIGINT        PK
public_id         UUID          UNIQUE NOT NULL
session_id        VARCHAR(50)   UNIQUE NOT NULL FK -> session(session_id)
score_content     INT           NULL      -- 내용 축
score_speech      INT           NULL      -- 음성 축
score_vision      INT           NULL      -- 시선 축
score_total       INT           NULL
summary           JSONB         NULL
growth_narrative  TEXT          NULL
timeline          JSONB         NULL
resilience        JSONB         NULL
report_data       JSONB         NULL      -- 컬럼으로 펴지 않은 원본 전체
status            VARCHAR(20)   NOT NULL
created_at        TIMESTAMPTZ   NOT NULL
```

**점수 4개가 전부 nullable 인 것은 부분 실패 때문입니다.** 음성·시선 분석이
실패해도 리포트 자체는 생성되고 그 축만 빕니다. 단 내용 분석이 실패하면 총점을
낼 수 없어 전체 실패입니다. [`13-report.md`](./13-report.md) 참고.

`report_data` 는 계약 확정 전까지 AI 응답을 잃지 않기 위한 보관함입니다.
**조회 조건으로 쓰지 마세요.** 확정되면 필요한 것만 컬럼으로 승격시킵니다.

---

## 회사

```sql
company
company_id       BIGINT        PK
company_name     VARCHAR(100)  UNIQUE NOT NULL
industry         VARCHAR(100)  NOT NULL
values_format    VARCHAR(20)   NOT NULL  -- WORD | PRINCIPLE | MIXED
core_values      JSONB         NOT NULL  -- [{"name":..., "behaviors":[...]}]
job_requirements JSONB         NULL      -- 직무를 키로 하는 요구역량
source_url       TEXT[]        NOT NULL  -- 출처. 복수 가능
talent_profile   TEXT          NULL
interview_style  VARCHAR(200)  NULL
verified         BOOLEAN       NOT NULL  -- 공식 채용페이지에서 직접 확인했는지
updated_at       DATE          NOT NULL  -- ★ 수정 시각이 아니라 수집일
created_at       TIMESTAMPTZ   NOT NULL
```

```sql
user_company_interest
id            BIGINT        PK
user_id       VARCHAR(50)   NOT NULL FK -> users(user_id)
company_id    BIGINT        NOT NULL FK -> company(company_id)
created_at    TIMESTAMPTZ   NOT NULL

UNIQUE (user_id, company_id)
```

### ★ company.updated_at 은 수집일입니다

이름은 `updated_at` 인데 타입이 `DATE` 이고 의미는 "이 정보를 언제 긁어왔는가"
입니다. 자동 갱신되면 안 되므로 자바 필드명은 `collectedOn` 으로 다릅니다.
`getUpdatedAt()` 으로 두면 감사 컬럼으로 착각합니다.

### values_format 을 PostgreSQL enum 으로 만들지 않습니다

`ddl-auto: update` 에서 Hibernate 가 기존 enum 타입에 값을 추가하지 못합니다.
항목이 하나 늘 때마다 손으로 `ALTER TYPE` 을 쳐야 합니다. `user_auth.provider`
와 같은 이유로 `VARCHAR` 에 이름을 담습니다.

---

## 성장 · 게이미피케이션

```sql
goal
goal_id       BIGINT        PK
user_id       VARCHAR(50)   NOT NULL FK -> users(user_id)
goal_type     VARCHAR(20)   NOT NULL
target_metric VARCHAR(30)   NOT NULL  -- 무엇을 세는지
target_value  INT           NOT NULL
start_date    DATE          NOT NULL
end_date      DATE          NOT NULL
status        VARCHAR(20)   NOT NULL
completed_at  TIMESTAMPTZ   NULL
created_at    TIMESTAMPTZ   NOT NULL
updated_at    TIMESTAMPTZ   NOT NULL
```

```sql
badge
badge_id       BIGINT        PK
badge_code     VARCHAR(50)   UNIQUE NOT NULL  -- 사람이 읽는 안정적인 키
badge_name     VARCHAR(50)   NOT NULL
description    VARCHAR(255)  NOT NULL
category       VARCHAR(20)   NOT NULL
condition_type VARCHAR(30)   NOT NULL
condition_data JSONB         NOT NULL  -- 예: {"days": 7}
icon_url       VARCHAR(500)  NULL
status         VARCHAR(20)   NOT NULL
created_at     TIMESTAMPTZ   NOT NULL
updated_at     TIMESTAMPTZ   NOT NULL
```

```sql
user_badge
user_badge_id     BIGINT        PK
user_id           VARCHAR(50)   NOT NULL FK -> users(user_id)
badge_id          BIGINT        NOT NULL FK -> badge(badge_id)
source_session_id VARCHAR(50)   NULL FK -> session(session_id)
earned_at         TIMESTAMPTZ   NOT NULL

UNIQUE (user_id, badge_id)
```

```sql
streak
streak_id     BIGINT        PK
user_id       VARCHAR(50)   NOT NULL FK -> users(user_id)
activity_date DATE          NOT NULL
answer_count  INT           NOT NULL  -- CHECK >= 1
created_at    TIMESTAMPTZ   NOT NULL
updated_at    TIMESTAMPTZ   NOT NULL

UNIQUE (user_id, activity_date)
```

### ★ 연속 일수를 저장하지 않습니다

`streak` 은 날짜별 행만 쌓고 연속 여부는 조회할 때 계산합니다. 숫자를 들고
있으면 자정을 넘기는 요청, 시간대, 중간 실패 때 실제 기록과 어긋나기 시작합니다.

### ★ 획득 기록이 있는 배지는 지우지 마세요

`user_badge.badge_id` 에 삭제 정책을 걸지 않았습니다. PostgreSQL 기본
`NO ACTION` 이 삭제를 막아줍니다. 더 안 주고 싶으면 지우지 말고 `status` 를
내리세요.

`badge_code` 가 안정적인 키입니다. 코드에서 배지를 지목할 때 `badge_id` 대신
이걸 쓰세요. 시드를 다시 넣어도 안 바뀝니다.

---

## 즐겨찾기 · 프로필 · 알림 · 일정

```sql
bookmarked_question
bookmarked_question_id BIGINT      PK
user_id                VARCHAR(50) NOT NULL FK -> users(user_id)
session_id             VARCHAR(50) NOT NULL  -- FK -> question (복합)
question_id            VARCHAR(50) NOT NULL  -- FK -> question (복합)
memo                   VARCHAR(500) NULL
created_at             TIMESTAMPTZ NOT NULL
updated_at             TIMESTAMPTZ NOT NULL

UNIQUE (user_id, session_id, question_id)
```

`question.is_bookmarked` 와 짝인 비정규화 플래그가 있습니다. 목록 조회에서
조인을 피하려는 용도이며 **이 행을 만들거나 지울 때 그 플래그도 같이 바꿔야
합니다.** 둘이 어긋나면 목록과 상세가 다르게 보입니다.

```sql
user_profile
user_profile_id   BIGINT        PK
user_id           VARCHAR(50)   UNIQUE NOT NULL FK -> users(user_id)
profile_image_url VARCHAR(500)  NULL
introduction      VARCHAR(500)  NULL
target_job_role   VARCHAR(50)   NULL
career_level      VARCHAR(20)   NULL
school_name       VARCHAR(100)  NULL
major             VARCHAR(100)  NULL
graduation_year   INT           NULL
created_at        TIMESTAMPTZ   NOT NULL
updated_at        TIMESTAMPTZ   NOT NULL
```

전 컬럼이 nullable 입니다. 없어도 서비스가 도는 값만 모았으므로 가입 시점에
만들지 않아도 됩니다.

```sql
notification_setting
notification_setting_id BIGINT      PK
user_id                 VARCHAR(50) UNIQUE NOT NULL FK -> users(user_id)
calendar_enabled        BOOLEAN     NOT NULL
goal_enabled            BOOLEAN     NOT NULL
report_enabled          BOOLEAN     NOT NULL
badge_enabled           BOOLEAN     NOT NULL
streak_enabled          BOOLEAN     NOT NULL
marketing_enabled       BOOLEAN     NOT NULL
created_at              TIMESTAMPTZ NOT NULL
updated_at              TIMESTAMPTZ NOT NULL
```

**이쪽은 반대로 가입 시점에 만들어야 합니다.** 전 컬럼이 NOT NULL 이라 행이
없으면 "설정 안 함"과 "끔"을 구분할 수 없습니다. `marketing_enabled` 만 기본값이
false 입니다. 광고성 정보는 수신 동의를 받아야 보낼 수 있습니다.

```sql
calendar_event
calendar_event_id BIGINT        PK
user_id           VARCHAR(50)   NOT NULL FK -> users(user_id)
company_name      VARCHAR(100)  NOT NULL  -- ★ 직접 입력. company FK 아님
job_role          VARCHAR(50)   NOT NULL
interview_round   VARCHAR(30)   NULL
scheduled_at      TIMESTAMPTZ   NOT NULL
interview_method  VARCHAR(10)   NOT NULL  -- ONLINE | OFFLINE
location          VARCHAR(200)  NULL      -- OFFLINE 만
meeting_url       VARCHAR(500)  NULL      -- ONLINE 만
memo              TEXT          NULL
created_at        TIMESTAMPTZ   NOT NULL
updated_at        TIMESTAMPTZ   NOT NULL
```

`company_name` 이 문자열이고 `company` FK 가 아닙니다. **우리가 수집해둔 기업
목록에 없는 회사에도 지원하기 때문입니다.** FK 로 묶으면 목록에 없는 회사의
일정을 아예 등록할 수 없게 됩니다.

---

## 삭제 정책

```
users 삭제      → 종속 테이블 전부 CASCADE
session 삭제    → question, report CASCADE
question 삭제   → answer, bookmarked_question CASCADE
session 삭제    → retry_of_session_id, user_badge.source_session_id SET NULL
folder 삭제     → session.folder_id SET NULL
badge 삭제      → user_badge 가 막음 (NO ACTION)
company 삭제    → session.company_id 가 막음 (NO ACTION)
document 삭제   → session.document_id 가 막음 (NO ACTION)
```

---

## 인덱스

```sql
CREATE INDEX idx_session_user       ON session(user_id, created_at);
CREATE INDEX idx_session_retry_root ON session(retry_of_session_id);
CREATE INDEX idx_session_folder     ON session(folder_id);
CREATE INDEX idx_qlog_session       ON question(session_id, question_number);
CREATE INDEX idx_document_user      ON document(user_id);
CREATE INDEX idx_folder_user        ON folder(user_id);
CREATE INDEX idx_company_industry   ON company(industry);
CREATE INDEX idx_company_verified   ON company(verified);
CREATE INDEX idx_goal_user_status   ON goal(user_id, status);
CREATE INDEX idx_user_badge_user_earned ON user_badge(user_id, earned_at);
CREATE INDEX idx_user_badge_badge       ON user_badge(badge_id);
CREATE INDEX idx_bookmarked_question_user ON bookmarked_question(user_id, created_at);
CREATE INDEX idx_calendar_event_user_scheduled ON calendar_event(user_id, scheduled_at);
CREATE INDEX idx_user_company_interest_company ON user_company_interest(company_id);
```

`idx_session_retry_root` 는 회차 목록 조회(같은 루트를 가진 세션 전부)에 씁니다.

`user_auth` 는 인덱스를 따로 만들지 않습니다. 로그인 조회에 쓰는
`(provider, provider_id)` 와 `(user_id, provider)` 는 UNIQUE 제약이 이미 인덱스를
만듭니다. 같은 걸 두 번 만들면 쓰기만 느려집니다.
