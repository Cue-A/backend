# 02. 데이터베이스

PostgreSQL 16. `ddl-auto: update` 로 운영합니다. **`create` 를 쓰지 마세요.**

---

## 사용자

```sql
user_id       VARCHAR(50)   PK
email         VARCHAR(255)  UNIQUE NOT NULL
password      VARCHAR(255)  NOT NULL
nickname      VARCHAR(50)   NOT NULL
created_at    TIMESTAMP     NOT NULL
```

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
