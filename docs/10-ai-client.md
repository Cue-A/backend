# 10. AI 서버 연동

`infrastructure/ai/` 가 담당합니다. 이 프로젝트에서 가장 중요한 계층이며,
참고할 오픈소스가 없으므로 직접 설계합니다.

**도메인 서비스에서 `RestClient` 를 직접 쓰지 않습니다.** 반드시 이 계층을 통합니다.

---

## 엔드포인트

Base URL은 프로파일 설정값(`app.ai.base-url`, 환경변수 `APP_AI_BASE_URL`)을 씁니다.

**실제 AI 서버는 학과 GPU 서버에서 운영됩니다.** Whisper STT·시선 분석에 GPU 가
필요해 별도 GPU 서버에서 돌고, 고정 HTTPS 주소를 제공받아 `APP_AI_BASE_URL` 에
넣습니다. Backend 가 어디에 배포되든(로컬·학교 서버·클라우드) 같은 AI 주소로
호출합니다. 로컬 개발은 `http://localhost:8000`(더미) 또는 `mock` 이 기본입니다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/ai/sessions` | 세션 시작 |
| POST | `/ai/sessions/{sessionId}/answers` | 답변 제출 |
| GET | `/ai/tasks/{taskId}` | 작업 상태 조회 (폴링) |
| POST | `/ai/sessions/{sessionId}/abort` | 세션 중단 |

**`GET /ai/companies` 는 없습니다.** 기업 데이터의 Source of Truth 는 Backend
`company` 테이블입니다. 면접 시작 시 선택된 기업의 인재상을
`company_profile_override` 문자열로 조립해 보내며, AI 서버는 그 텍스트를 질문
생성에 반영할 뿐 저장하지 않습니다. 아래 "회사 정보 전달" 절 참고.

**AI 문서 인덱싱 엔드포인트도 없습니다.** 초기 설계의 RAG·벡터 인덱싱은 폐기됐고,
문서 등록 때 AI 를 호출하지 않습니다(`POST /api/documents` → 저장 → 즉시 `READY`).
이력서는 색인하지 않고 면접 시작 시 전체 내용을 AI 에 넘깁니다. 아래 "이력서 전달"
절 참고.

요청·응답 JSON은 **snake_case** 입니다. `infrastructure/ai/dto` 에만
`@JsonNaming(SnakeCaseStrategy.class)` 를 붙이고 경계에서 변환합니다.

---

## 비동기 + 폴링

모든 생성 작업은 비동기입니다. 요청하면 `task_id` 를 즉시 받고, 폴링으로 결과를 얻습니다.

```
POST /ai/sessions          →  202  { session_id, task_id, question_total }
GET  /ai/tasks/{task_id}   →  { status: "processing", stage: "stt" }
GET  /ai/tasks/{task_id}   →  { status: "done", result: { ... } }
GET  /ai/tasks/{task_id}   →  { status: "error", error_code: "STT_FAILED", message: "..." }
```

폴링 간격은 **1초**입니다. 상태는 `processing` | `done` | `error` 세 가지이며,
실패는 `error`(+`error_code`)로 옵니다. `AiTaskStatusResponse.isFailed()` 는 최신
계약의 `error` 와 레거시/mock 의 `failed` 를 모두 실패로 인식하고, `error_code` 를
`AiErrorTranslator` 로 옮겨 타임아웃으로 오인하지 않게 합니다.

## 세션 시작 요청

```json
{
  "resume_file_url": "https://s3.../resume_abc.pdf",
  "job_role": "백엔드 개발",
  "persona": "pressure",
  "company_id": "17",
  "company_profile_override": "현대건설(주) (종합건설 · 플랜트)\n핵심 가치\n  도전 — 새로운 시도를 두려워하지 않는다",
  "question_count": 9,
  "retry_of_session_id": null,
  "doc_id": null
}
```

```
resume_file_url           필수. 백엔드가 발급한 presigned URL(만료 15분)
job_role                  필수. 자유 문자열
persona                   필수. "friendly" | "pressure" — Persona.toAiValue() 로 직렬화
company_id                Backend company.company_id(BIGINT PK)를 문자열로 변환한 값.
                           AI 는 조회 key 가 아니라 로그·추적용 opaque ID 로만 씁니다.
                           회사 미선택이면 null (JSON 에서 빠짐)
company_profile_override  기업을 선택했으면 반드시 채웁니다. CompanyProfileFormatter
                           가 company.core_values 로 조립합니다. 미선택이면 null
question_count            선택. 3 | 6 | 9. 기본값 6
retry_of_session_id       선택. 재연습이면 최초 세션 ID
doc_id                    예약 필드. 항상 null. RAG/인덱스 ID 가 아닙니다(아래 절 참고)
```

**질문 생성용 기업 정보는 `company_id` 가 아니라 `company_profile_override` 로
전달합니다.** Backend 의 `company` 테이블이 기업 데이터의 Source of Truth 이며,
AI 서버는 기업 목록을 갖지 않습니다. `docs/02-database.md` 의 core_values 절 참고.

### 타임아웃 — 두 종류로 분리

| 작업 | 예상 소요 | 타임아웃 |
|---|---|---|
| 세션 시작 (주질문 여러 개 + TTS) | 10~30초 | **90초** |
| 답변 처리 (STT + LLM + TTS) | 5~15초 | **60초** |

단일 60초로 두면 세션 시작에서 오탐이 발생합니다. 반드시 분리하세요.

타임아웃 초과 시 `POST /ai/sessions/{id}/abort` 를 호출하고 세션을 `aborted` 로
전이합니다. 이걸 만들지 않으면 AI가 죽었을 때 세션이 영원히 진행 중 상태로 남습니다.

### 트랜잭션 밖에서

폴링은 최대 90초입니다. **트랜잭션 안에서 하면 DB 커넥션을 그만큼 붙듭니다.**
[`01-conventions.md`](./01-conventions.md) 의 트랜잭션 항목 참고.

가상 스레드가 켜져 있으므로 블로킹 폴링을 써도 됩니다.

### HTTP 요청 스레드 밖에서 (비동기)

AI 계약은 `task_id` 를 즉시 반환하는 비동기 모델입니다. 폴링(최대 90초)을 HTTP
요청 스레드에서 기다리면 REST 응답이 그만큼 늦어집니다. 그래서 세션 시작은
아래처럼 나눕니다.

```
Front  ──POST /api/interviews──▶  Spring
                                    ├─ Document/Company 조회
                                    ├─ POST /ai/sessions  (session_id, task_id 수신)
                                    ├─ session 저장 (IN_PROGRESS)
                                    └─ 202 응답 즉시 반환 { sessionId, questionTotal }
                                        │
                                        └─(background, 가상 스레드 @Async)
                                            ├─ task_id 폴링
                                            ├─ 첫 Question 저장
                                            └─ WebSocket push
```

폴링·저장·전달은 `InterviewFirstQuestionPoller`(`@Async`, 가상 스레드 실행기)가
맡습니다. `@Async` 는 프록시를 거쳐야 하므로 진입 서비스와 **다른 빈**으로
분리합니다. 프론트는 REST 응답을 받는 즉시 `/ws/interviews/{sessionId}` 에
연결해 첫 질문·진행 상황·오류 push 를 받습니다.

백그라운드 폴링이 실패하면 REST 는 이미 반환된 뒤이므로, 세션을 `ABORTED` 로
정리하고 오류를 WebSocket(`type: "error"`)으로 알립니다.

---

## 응답 타입

`status: "done"` 일 때 `result.type` 이 네 가지입니다.

| type | 설명 | question_number |
|---|---|---|
| `question` | 주질문 | 증가 |
| `followup` | 꼬리질문 | 증가 |
| `reask` | 되묻기 | **증가하지 않음** |
| `session_end` | 세션 종료 | — |

`reask` 는 문항 수에 포함되지 않습니다. 진행률 계산에서 제외하세요.

### 공통 필드

```json
{
  "type": "question",
  "question_id": "q_4",
  "text": "왜 낙관적 락을 선택하셨나요?",
  "audio_url": "https://.../q_4.mp3",
  "category": "프로젝트경험",
  "difficulty": "L2",
  "question_number": 4,
  "question_total": 9,
  "topic_index": 2,
  "topic_total": 4,
  "is_spare_topic": false,
  "is_replay": false
}
```

- `is_spare_topic`, `is_replay` 는 **항상 포함**됩니다. nullable 처리 불필요
- `reask` 는 `category`, `difficulty` 만 null이고 나머지는 채워지며, `reask_of` 로
  원 질문의 `question_id` 를 알려줍니다. 그 외 타입은 `reask_of` 가 없습니다
- `audio_url` 은 TTS 생성 실패 시 null 입니다. 이때도 task 는 `status: "done"` 이며
  `text` 는 채워집니다(아래 "에러 처리 — TTS 실패는 error 가 아닙니다" 참고)

---

## WebSocket push

폴링 결과를 프론트에 밀어줍니다. 프론트 ↔ Spring 메시지 스키마는 백엔드가
자유롭게 설계합니다. AI 응답 형식을 그대로 쓸 필요 없습니다.

### ★ stage 를 그대로 내보내지 마세요

AI의 `stage` 값은 `"stt" | "generating" | "tts"` 입니다.
이걸 프론트에 그대로 보내면 **AI 내부 파이프라인 이름이 프론트 코드에 박힙니다.**
AI가 파이프라인을 바꾸면 프론트가 깨집니다.

Spring 자체 enum으로 매핑합니다.

```java
public enum ProgressStage {
    TRANSCRIBING,   // stt
    GENERATING,     // generating
    SYNTHESIZING    // tts
}
```

---

## 에러 처리

### 실패는 HTTP 상태가 아니라 폴링 결과로 옵니다

`startSession` · `submitAnswer` 는 비동기 task 방식입니다. POST 는 `202` + `task_id`
를 돌려주고, 생성 실패는 **폴링 결과의 `status: "error"`** 로 내려옵니다. HTTP 500
으로 오는 동기 오류가 아닙니다.

```json
GET /ai/tasks/{task_id}
{
  "status": "error",
  "error_code": "STT_FAILED",
  "message": "..."
}
```

`AiTaskStatusResponse.isFailed()` 가 이 `error`(및 레거시/mock 의 `failed`)를 실패로
인식하고, `error_code` 를 `AiErrorTranslator` 로 우리 `ErrorCode` 에 옮깁니다.
`SESSION_NOT_FOUND` 처럼 요청 자체가 거부되는 일부 코드는 AI 가 4xx/5xx 로 줄 수
있고, `RealAiClient` 의 `defaultStatusHandler` 가 같은 `AiErrorTranslator` 로 옮겨
동일하게 처리합니다.

### error_code 별 처리

| error_code | 전달 방식 | 재시도 | 처리 |
|---|---|---|---|
| `LLM_FAILED` | 폴링 `status:error` | 1회 | 같은 요청 재전송 |
| `STT_FAILED` | 폴링 `status:error` | 1회 | 재시도도 실패하면 재녹음 안내 |
| `SESSION_NOT_FOUND` | 4xx | ❌ | 세션 `ABORTED` |
| `RESUME_PARSE_FAILED` | 폴링 `status:error` | ❌ | 세션 `ABORTED`. 다른 파일 안내 |
| `SESSION_ENDED` | 폴링/4xx | ❌ | 중복 제출로 보고 무시(답변) / cleanup+`ABORTED`(첫 질문) |
| `INVALID_QUESTION_ID` | 폴링/4xx | ❌ | 최초엔 세션 유지·통지 / 재시도 이후면 `ABORTED` |
| `INVALID_CATEGORY` | 폴링/4xx | ❌ | 세션 유지·통지 (재연습 조립 버그) |
| `AI_TIMEOUT` · `AI_UNAVAILABLE` | 응답 없음 | ❌ | cleanup + `ABORTED` |

이 분류는 `AiErrorTranslator`(dev 의 `isRetryable`·`requiresSessionAbort`·
`isAudioOnlyFailure`·`needsRerecord` 등)가 판단하며, error_code 별 재시도·중복 제출·
세션 정리 흐름은 Issue #25(PR #43)에서 구현합니다. 세션·재시도 흐름의 상세는
[`11-interview.md`](./11-interview.md) (Issue #25) 를 봅니다.

### TTS 실패는 error 가 아닙니다

**`TTS_FAILED` 라는 task error 는 현재 계약에 없습니다.** TTS 생성이 실패하면 task 는
정상 완료됩니다.

```json
{ "status": "done", "result": { "text": "...", "audio_url": null } }
```

`result.text` 는 채워지고 `audio_url` 만 `null` 입니다. Backend 는 이를 **음성 없는
텍스트 질문**으로 정상 처리하고, 프론트에도 음성이 없음을 알립니다
(`AiErrorTranslator.isAudioOnlyFailure`). 질문 로그의 `audio_url` 도 `null` 로
저장됩니다([`02-database.md`](./02-database.md) 의 question 테이블).

### 재시도·세션 실패 정책

> 아래는 AI 파트와 확정한 계약입니다. 이 계약대로의 재시도·중복 제출·세션 정리
> 흐름은 Issue #25(PR #43)에서 구현합니다. dev 의 폴러는 현재 실패를 통지만 하고,
> error_code 별 재시도/정리는 #43 이 들어온 뒤 동작합니다.

**세션 시작** — 주질문 생성 중 LLM 오류가 나면 AI 가 내부적으로 같은
`session_id`/`task_id` 를 유지하며 1회 재시도합니다. **Backend 는 `startSession`
자체를 재전송하지 않습니다.** Backend 가 최종 `LLM_FAILED` 를 받았다는 건 AI 내부
재시도까지 실패했다는 뜻이므로 cleanup 후 `ABORTED` 로 둡니다.

**답변 처리** — `STT_FAILED`/`LLM_FAILED` 가 폴링 `status:error` 로 확인된 뒤에만
Backend 가 동일 request body 를 **최대 1회**(`AiErrorTranslator.MAX_RETRY`) 재전송
합니다. 재전송하면 AI 가 새 `task_id` 를 발급합니다.

- 이전 task 가 `processing` 인 동안에는 재전송하지 않습니다. 두 task 가 동시에 돌면
  질문 진행 상태가 꼬입니다
- 재전송 POST 에서 `AI_TIMEOUT`/`AI_UNAVAILABLE` 같은 새 오류가 나오면 최초 STT/LLM
  으로 가리지 않고 그 최신 `error_code` 정책을 적용합니다

| 상황 | Backend 처리 |
|---|---|
| STT → retry 성공 | 정상 진행 |
| STT → retry 도 STT | 재녹음 안내, 세션 `IN_PROGRESS` 유지 |
| LLM → retry 성공 | 정상 진행 |
| LLM → retry 도 LLM | cleanup + `ABORTED` |
| retry 이후 `INVALID_QUESTION_ID` | AI·Backend 진행 상태 불일치로 보고 cleanup + `ABORTED` |
| 최초 `INVALID_QUESTION_ID` | 세션 유지, 오류만 통지 |
| `INVALID_CATEGORY` | 세션 유지, 오류만 통지 |
| 답변 처리 중 `SESSION_ENDED` | 중복 제출로 보고 무시 (답변 폴러, #25) |
| 첫 질문 생성 중 `SESSION_ENDED` | 비정상 상태로 보고 cleanup + `ABORTED` (첫 질문 폴러, #25) |

### 인증

내부 통신이므로 JWT를 쓰지 않습니다. 공유 시크릿 헤더를 씁니다.

```
X-Cueanda-Secret: ${APP_AI_SECRET}
```

이미 구현돼 있습니다. `RealAiClient` 가 `RestClient.defaultHeader(...)` 로 모든 나가는
요청(세션 시작·답변 제출·task 조회·abort)에 붙이고, 값은 `AiProperties.secret()`
(`app.ai.secret: ${APP_AI_SECRET:local-dev-only-ai-secret}`)에서 옵니다. 헤더 이름은
`AiSecretFilter.HEADER` 상수 하나로 관리합니다. AI 가 Backend 를 부르는 콜백
경로(`/api/internal/**`)가 생기면 같은 시크릿을 `AiSecretFilter` 가 검증합니다.
`mock` 모드에서는 `MockAiClient` 가 대신 응답하므로 시크릿을 쓰지 않습니다.

**실제 시크릿 값은 문서에 적지 않습니다.** 기본값 `local-dev-only-ai-secret` 은
로컬 전용이며, 배포에서는 `APP_AI_SECRET` 환경변수로 주입합니다. AI 서버는 내부망에
두고 외부 노출을 막습니다.

---

## 세션 상태 소유권

AI 서버가 자체적으로 세션 상태(진행 중인 토픽, 꼬리질문 횟수, 난이도 배분,
중복 방지)를 들고 있습니다. Spring DB의 `status` 와는 다른 개념입니다.

**사용자에게 보이는 상태는 Spring DB 기준입니다.**

> AI 서버를 재배포하면 진행 중이던 세션이 사라집니다. 개발 기간에 테스트 중
> `SESSION_NOT_FOUND` 가 뜨면 버그가 아닐 수 있습니다. AI 파트가 재배포 전에
> 공유하기로 했습니다.

---

## 회사 정보 전달

**AI 서버는 기업 목록을 갖지 않습니다.** 기업 데이터의 Source of Truth 는
Backend `company` 테이블이며, 면접 시작·리포트 요청 때 선택된 기업의 인재상을
`company_profile_override` 문자열로만 전달합니다.

### 조립 형식

`CompanyProfileFormatter` 가 `company.core_values`(`[{name, indicator}]` 구조)를
읽어 아래 형식의 문자열을 만듭니다.

```
기업명 (업종)
핵심 가치
  가치 이름 — 행동지표
  가치 이름 — 행동지표
```

실제 예시:

```
현대건설(주) (종합건설 · 플랜트)
핵심 가치
  도전 — 새로운 시도를 두려워하지 않는다
  신뢰 — 약속한 품질과 일정을 지킨다
```

이 문자열을 `AiSessionStartRequest.companyProfileOverride` 에 담아 보냅니다.
기업을 선택하지 않은 연습 모드면 `company_profile_override` 는 null 입니다.

### verified 필터

`verified=false` 인 기업은 서비스에 노출하지 않습니다. 조회 시점에
`CompanyRepository.findByCompanyIdAndVerifiedTrue` 로 걸러, 미검증 기업 ID 로는
세션을 시작할 수 없습니다. 공식 채용페이지에서 확인되지 않은 내용으로 질문을
만들면 틀린 정보가 나갑니다.

### company_id 필드 — 로그·추적용 opaque ID

AI 는 `company_id` 로 기업 데이터를 **조회하지 않습니다.** AI 팀 확정 사항:
`company_id` 는 AI 내부 기업 데이터 조회 key 가 아니라 로그·문제 추적용 식별자이며,
값 형식은 Backend 가 정합니다.

따라서 Backend 는 **`company.company_id`(BIGINT PK)를 문자열로 변환해** 보냅니다.

```
company.company_id = 17  →  "company_id": "17"
```

- AI 는 이 값을 기업 조회에 쓰지 않습니다. 로그·추적용 opaque ID 입니다.
- 실제 질문 생성용 기업 정보는 `company_profile_override` 로 전달합니다.
- `companies.json` 의 과거 문자열 ID(예: `"sk_hynix"`)는 AI 연동 ID 로 쓰지 않습니다.
- 별도 `ai_company_id` 컬럼은 두지 않습니다.
- 회사를 선택하지 않은 연습 모드면 `company_id` 는 null 이며, `@JsonInclude(NON_NULL)`
  로 인해 전송 JSON 에서 키 자체가 빠집니다.
- `GET /ai/companies` 는 사용하지 않습니다.

---

## 이력서 전달

**RAG 를 쓰지 않습니다.** 초기 설계의 RAG·FAISS·vector DB·vector index volume·AI
문서 인덱싱 엔드포인트는 모두 폐기됐습니다. 이력서는 분할·검색하지 않고 전체를 그대로
Claude 에 전달합니다.

문서 등록 시점에는 AI 를 호출하지 않습니다.

```
POST /api/documents  →  Backend 저장  →  즉시 READY
```

면접을 시작할 때 비로소 AI 가 이력서를 읽습니다.

```
Backend ──POST /ai/sessions──▶  { "resume_file_url": "<presigned GET URL>", ... }

AI  → URL 에서 직접 다운로드
    → PDF 는 그대로, DOCX·TXT 는 텍스트 추출
    → 이력서 전체를 Claude 에 전달해 주질문 생성
```

지원 조건:

- 형식: PDF · DOCX · TXT (최대 10MB)
- presigned GET 만료는 최소 10분. Backend 의 이력서 GET 은 15분입니다
  ([`20-storage.md`](./20-storage.md))
- 마크다운 문서(Issue #39)는 본문을 UTF-8 TXT 사본으로 S3 에 올려두고, 면접 시작 시
  그 TXT object 의 presigned GET 을 넘기므로 위 지원 형식과 맞습니다
  ([`02-database.md`](./02-database.md) 의 문서 절)

## doc_id — 예약 필드

`AiSessionStartRequest.doc_id` 는 **현재 항상 `null`** 입니다.

- Backend → AI 로 `null` 을 보냅니다(회사 미선택 `company_id` 처럼 `NON_NULL` 로 키가
  빠질 수 있음)
- AI 는 세션 시작 응답에 `doc_id` 를 넣지 않습니다
- Backend 는 저장하지 않습니다. 관련 DB 컬럼도 없습니다([`02-database.md`](./02-database.md))

이 필드는 **RAG/인덱스 ID 가 아니라** 향후 이력서 파싱 결과를 재사용하기 위한 예약
키입니다. 기능이 켜질 때의 순서:

1. AI 가 먼저 계약을 바꿔 세션 시작 응답에 `doc_id` 를 추가
2. Backend 가 컬럼을 만들어 저장
3. 이후 요청에 `doc_id` 를 전달

**지금은 선행 구현이나 DB 컬럼을 만들지 않습니다.**

---

## 개발용 목

AI 서버가 없을 때 고정 응답을 반환하는 내부 목을 둡니다.

```yaml
app:
  ai:
    mock:
      enabled: true
```

`AiClient` 인터페이스에 `RealAiClient` / `MockAiClient` 두 구현을 두고
`@ConditionalOnProperty` 로 갈아끼웁니다. 목이 없으면 AI 서버가 나올 때까지
폴링·WebSocket·로그 저장을 전혀 검증할 수 없습니다.
