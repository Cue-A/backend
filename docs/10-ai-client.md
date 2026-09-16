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
doc_id                    예약 필드. 항상 null
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
- `audio_url` 은 `TTS_FAILED` 시 null입니다

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

### 재시도 분류

| errorCode | HTTP | 재시도 | 처리 |
|---|---|---|---|
| `LLM_FAILED` | 500 | 1회 | 같은 요청 재전송 |
| `STT_FAILED` | 500 | 1회 | **실패 시 즉시 재녹음 안내** |
| `TTS_FAILED` | 500 | ❌ | `audio_url: null` 로 텍스트만 진행 |
| `SESSION_NOT_FOUND` | 404 | ❌ | 세션 `aborted` |
| `SESSION_ENDED` | 409 | ❌ | 무시 (중복 제출) |
| `INVALID_QUESTION_ID` | 400 | ❌ | 클라이언트 버그. 로그 후 오류 응답 |
| `INVALID_CATEGORY` | 400 | ❌ | 재연습 조립 버그. 로그 후 오류 응답 |
| `RESUME_PARSE_FAILED` | 422 | ❌ | 다른 파일 안내 |

`STT_FAILED` 는 무음·잡음·파일 손상이 원인인 경우가 많아 같은 오디오를 다시 넣어도
결과가 같습니다. 1회만 시도하고 바로 재녹음 흐름으로 넘깁니다.

`TTS_FAILED` 는 질문 텍스트가 이미 생성된 상태일 수 있습니다. 음성 없이 텍스트로
진행할 수 있어야 하며, **프론트에도 이 케이스를 알려야 합니다.**

### 인증

내부 통신이므로 JWT를 쓰지 않습니다. 공유 시크릿 헤더를 씁니다.

```
X-Cueanda-Secret: ${APP_AI_SECRET}
```

AI 서버는 내부망에 두고 외부 노출을 막습니다.

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
