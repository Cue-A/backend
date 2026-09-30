# 10. AI 서버 연동

`infrastructure/ai/` 가 담당합니다. 이 프로젝트에서 가장 중요한 계층이며,
참고할 오픈소스가 없으므로 직접 설계합니다.

**도메인 서비스에서 `RestClient` 를 직접 쓰지 않습니다.** 반드시 이 계층을 통합니다.

---

## 엔드포인트

Base URL은 프로파일 설정값(`app.ai.base-url`)을 씁니다.

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
doc_id                    예약 필드. 항상 null (RAG/인덱스 id 아님. 향후 이력서 파싱
                           캐시 재사용용. 지금은 Backend 가 저장·전달하지 않음)
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
- `audio_url` 은 AI 가 질문 TTS 음성을 올린 **서명 없는** S3 URL 이며, TTS 실패·비활성
  시 null 입니다. Backend 는 이 URL 을 프론트에 그대로 노출하지 않고, WebSocket 질문
  push 시점에 같은 object 에 대한 presigned GET 을 발급해 내려줍니다(아래 "질문 음성"
  절, [`20-storage.md`](./20-storage.md))

### 질문 음성 (audio_url)

AI 는 질문 TTS 음성을 private 버킷의
`sessions/{sessionId}/questions/{questionId}.mp3` 에 직접 올리고, task 결과의
`audio_url` 로 **서명 없는** S3 URL 을 돌려줍니다. 버킷이 private 이라 프론트가 그
URL 을 그대로 GET 하면 403 이므로, Backend 는 그 값을 프론트에 그대로 넘기지 않습니다.

- `audio_url != null` 이면 Backend 가 AI URL 을 parsing 하지 않고 계약상 고정 key
  규칙으로 object key 를 만들어 presigned GET 을 발급합니다. DB(`question.audio_url`)에는
  AI 가 준 안정적인 원본 URL 을 그대로 두고, presigned URL 은 저장하지 않습니다
- `audio_url == null`(TTS 없음)이거나 presign 발급이 실패하면 음성 없이 텍스트로
  진행합니다(세션은 유지, 중단하지 않음)

프론트 관점의 WebSocket 필드(`audioUrl`·`audioAvailable`)는
[`11-interview.md`](./11-interview.md), 저장·경로·TTL 은 [`20-storage.md`](./20-storage.md)
를 봅니다.

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

실제 모드에서 stage 순서는 단계마다 다릅니다.

```
세션 시작   generating → tts        (stt 없음)
답변 처리   stt → generating → tts
```

1초 간격 폴링 사이에 stage 가 지나가면 일부 단계는 관측되지 않을 수 있으니, 특정
stage 가 반드시 보인다고 가정하지 마세요. 더미(mock) 세션 시작에서는 `stt` 가 보일 수
있는데, 이는 실제 모드와 다른 더미 동작입니다.

---

## 에러 처리

### 실패는 HTTP 상태가 아니라 폴링 결과로 옵니다

`startSession`·`submitAnswer` 는 `202` + `task_id` 를 돌려주고, 생성 실패는 **폴링
결과의 `status: "error"` + `error_code`** 로 내려옵니다. HTTP 500 으로 오는 동기
오류가 아닙니다(`SESSION_NOT_FOUND` 처럼 요청 자체가 거부되는 일부만 4xx/5xx 로 올
수 있고, `RealAiClient` 가 같은 `AiErrorTranslator` 로 옮겨 동일하게 처리합니다).

### error_code 별 처리 (질문 생성·답변)

| error_code | 재시도 | 처리 |
|---|---|---|
| `LLM_FAILED` (답변 처리) | Backend 1회 재전송 | 재전송도 실패하면 `ABORTED` |
| `LLM_FAILED` (세션 시작) | Backend 재시도 없음 | AI 내부 1회 재시도까지 실패한 최종 상태. cleanup + `ABORTED` |
| `STT_FAILED` (답변 처리) | Backend 1회 재전송 | 재전송도 실패하면 재녹음 안내(`needsRerecord=true`), 세션 `IN_PROGRESS` 유지 |
| `SESSION_NOT_FOUND` | ❌ | 세션 `ABORTED` |
| `RESUME_PARSE_FAILED` | ❌ | 세션 `ABORTED`. 다른 파일 안내 |
| `SESSION_ENDED` | ❌ | 답변 처리 중이면 중복 제출로 무시 / 첫 질문 생성 중이면 cleanup + `ABORTED` |
| `INVALID_QUESTION_ID` | ❌ | 최초엔 세션 유지·통지 / 재전송 이후면 `ABORTED` |
| `INVALID_CATEGORY` | ❌ | 세션 유지·통지 (재연습 조립 버그) |
| `AI_TIMEOUT` · `AI_UNAVAILABLE` | ❌ | cleanup + `ABORTED` |

**`LLM_FAILED` 는 단계에 따라 다릅니다.** 세션 시작에서는 AI 가 같은 task 안에서 1회
재시도하므로 Backend 가 받는 `LLM_FAILED` 는 이미 최종 실패이고 재전송하지 않습니다.
답변 처리에서만 Backend 가 동일 요청을 폴링 `status:error` 확인 후 1회 재전송하며,
재전송하면 새 `task_id` 가 발급됩니다(`processing` 중에는 재전송하지 않음). 세션·재시도
흐름의 사용자 관점 정리는 [`11-interview.md`](./11-interview.md)(Issue #25) 참고.

### TTS 실패·비활성은 error 가 아닙니다

**`TTS_FAILED` 라는 task error 는 실제로 발생하지 않습니다.** TTS 가 실패하거나 비활성
이거나 더미면 task 는 정상 완료됩니다.

```json
{ "status": "done", "result": { "text": "...", "audio_url": null } }
```

`result.text` 는 채워지고 `audio_url` 만 `null` 입니다. Backend 는 이를 **음성 없는
텍스트 질문**으로 정상 처리합니다(WebSocket 질문 push 의 `audioAvailable=false`).
질문 음성이 있을 때 프론트로 내려가는 `audio_url` 의 의미는 아래 "질문 음성" 절과
[`20-storage.md`](./20-storage.md) 를 봅니다.

### 인증

내부 통신이지만 실제 AI 서버는 학과 GPU 서버에 공개 `https` 주소로 열려 있어, JWT
대신 요청마다 공유 시크릿 헤더로 인증합니다.

```
X-Cueanda-Secret: ${APP_AI_SECRET}
```

이 시크릿이 유일한 보호 수단이라 값이 AI 쪽과 다르면 모든 요청이 거부됩니다. AI
서버 쪽에서 `/health`·`/ready` 만 이 검증의 예외이고, 나머지 실제 엔드포인트는 모두
헤더를 요구합니다. Backend 는 `RealAiClient` 가 모든 나가는 요청에 이 헤더를 붙입니다
(`app.ai.secret`, 값은 문서에 적지 않습니다).

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

### 리포트 실패 재현

목의 리포트는 AI 저장소 더미 서버(`ai/report_dummy.py`)와 **같은 규칙**으로 실패합니다.
요청의 URL 은 답변의 object key 로 만들므로, 리포트를 요청하기 전에 key 를 바꾸면 됩니다.
목과 더미 서버에서 같은 결과가 납니다.

| 조건 (대소문자 무시) | 결과 |
|---|---|
| `answer_audio_object_key` 에 `content_fail` | processing 뒤 `error` `CONTENT_FAILED` → 자동 재시도 1회 → `FAILED` |
| `answer_audio_object_key` 에 `fail` | 말하기 축 `failed` → `PARTIAL` |
| `answer_video_object_key` 에 `fail` | 시선 축 `failed` → `PARTIAL` |
| 영상 key 가 전부 없음 | 시선 `skipped`, `COMPLETED` (실패 아님) |

```sql
update question set answer_audio_object_key = 'audio/test/content_fail.webm'
 where session_id = '{sessionId}' and question_id = '{questionId}';
```

URL 이 그대로면 새 Idempotency-Key 로 다시 요청해도 또 실패합니다(더미 서버와 같음).

---

## 리포트 생성

면접이 끝난 세션의 분석을 요청합니다. 질문 생성과 같은 **task 등록 + 폴링** 구조입니다.
Backend 흐름은 [`13-report.md`](./13-report.md) 참고.

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/ai/sessions/{sessionId}/report` | 리포트 생성 요청. `202 { task_id }` |
| GET | `/ai/tasks/{taskId}` | 진행 상황 (질문과 같은 경로, 결과 모양만 다름) |

```
Idempotency-Key: rpt_{sessionId}_{attempt}
```

- **같은 키면 AI 가 새 작업을 만들지 않고 기존 task_id 를 돌려줍니다.** 그래서 실패한
  작업을 다시 돌릴 때는 반드시 `attempt` 를 올린 새 키를 씁니다. 같은 키로 보내면
  실패한 task 가 그대로 돌아옵니다
- 폴링 타임아웃은 **10분**(`app.report.poll-timeout`). 영상 다운로드와 시선 분석이
  포함돼 질문 생성보다 훨씬 깁니다
- 결과(`result`)는 해석하지 않고 원본 JSON 으로 받습니다(`AiReportTaskStatusResponse`).
  점수 몇 개만 꺼내고 나머지는 `report.report_data` 에 통째로 둡니다
- `processing` 응답에는 `stage` 와 `progress`(0~1)가 옵니다. stage 는
  `ReportProgressStage` 로 옮겨 프론트에 보냅니다

| error_code | 오는 곳 | Backend 처리 |
|---|---|---|
| `REPORT_TOO_SHORT` | 요청 422 | 그대로 422. 리포트 행을 만들지 않음 |
| `INVALID_ANSWERS` · `INVALID_REQUEST` | 요청 400 | 조립 버그. 사용자에게는 서버 오류 |
| `CONTENT_FAILED` | 폴링 `error` | 새 키로 자동 재시도 1회 |
| `MEDIA_FETCH_FAILED` | 폴링 `error` | presigned URL 새로 발급해 새 키로 자동 재시도 1회 |
| `STT_FAILED` | 폴링 `error` | 재시도 없음 |
| `SPEECH_FAILED` · `GAZE_FAILED` | 오류가 아님 | `status: done` + `report_status: partial`, 그 축만 `failed` |

### 연결 실패와 응답 지연을 나눕니다

`RealAiClient` 는 연결 자체가 안 되면 503 `AI_UNAVAILABLE`, 붙었는데
`read-timeout` 안에 답이 없으면 504 `AI_TIMEOUT` 을 냅니다. 둘 다
`ResourceAccessException` 으로 오지만 원인이 `SocketTimeoutException`(연결 타임아웃
제외)인지로 구분합니다. 질문 생성 호출에도 똑같이 적용됩니다.
