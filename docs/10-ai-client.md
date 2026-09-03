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
| GET | `/ai/companies` | 회사 목록 |
| POST | `/ai/sessions/{sessionId}/abort` | 세션 중단 |

요청·응답 JSON은 **snake_case** 입니다. `infrastructure/ai/dto` 에만
`@JsonNaming(SnakeCaseStrategy.class)` 를 붙이고 경계에서 변환합니다.

---

## 비동기 + 폴링

모든 생성 작업은 비동기입니다. 요청하면 `task_id` 를 즉시 받고, 폴링으로 결과를 얻습니다.

```
POST /ai/sessions          →  202  { session_id, task_id, question_total }
GET  /ai/tasks/{task_id}   →  { status: "processing", stage: "stt" }
GET  /ai/tasks/{task_id}   →  { status: "done", result: { ... } }
```

폴링 간격은 **1초**입니다.

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
- `reask` 는 `category`, `difficulty` 만 null이고 나머지는 채워집니다
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
X-CueA-Secret: ${APP_AI_SECRET}
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

## 회사 목록

`GET /ai/companies` 를 그대로 프론트에 프록시합니다.

**DB에 저장하지 않습니다.** 원본이 두 곳에 있으면 반드시 어긋납니다.
거의 바뀌지 않으므로 Redis에 **1시간** 캐시합니다.

```json
[
  { "company_id": "hyundai_enc", "name": "현대건설(주)", "industry": "종합건설 · 플랜트" }
]
```

미검증 회사는 AI 서버에서 걸러서 내보냅니다.

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
