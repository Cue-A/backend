# 11. 면접 세션

---

## 세션 흐름

```
1. 사용자가 문서·직무·페르소나·문항 수·(선택)기업 선택
2. Spring: POST /api/interviews  (동기 구간)
   → Document(READY)·Company(verified) 조회
   → resume_file_url(presigned) 발급, company_profile_override 조립
   → AI에 POST /ai/sessions → session_id·task_id 수신
   → session 저장(IN_PROGRESS)
   → REST 응답 즉시 반환 { sessionId, questionTotal }
3. Spring(백그라운드, @Async): task_id 폴링 (최대 90초) → 첫 질문 수신
4. Spring: 로그 저장 → WebSocket으로 프론트에 push
5. 사용자 답변 녹음 → S3 Presigned 업로드
6. Spring: AI에 POST /answers → 폴링 (최대 60초)
7. 다음 질문 / 되묻기 / 세션 종료 중 하나 수신
8. 4~7 반복
9. session_end 수신 → status = completed
```

**2단계 REST 응답은 첫 질문을 기다리지 않고 즉시 반환됩니다.** 폴링(3)과 첫 질문
전달(4)은 백그라운드에서 일어나므로, 프론트는 REST 응답을 받는 즉시
`/ws/interviews/{sessionId}` 에 연결해야 첫 질문 push 를 놓치지 않습니다.

> ⚠️ **알려진 제약 (후속 작업):** 백그라운드 폴링이 프론트의 WebSocket 핸드셰이크
> 보다 먼저 끝나면(특히 AI 가 빠르게 `done` 을 줄 때) 첫 질문 push 가 수신자 없이
> 드롭될 수 있습니다. 질문은 DB 에 저장되지만 프론트가 못 받는 상황입니다. pending
> 이벤트 버퍼나 세션 상태 조회(catch-up) 엔드포인트는 이번 PR 범위가 아니며 후속
> 작업으로 분리합니다. WebSocket 핸드셰이크 인증·소유권 검증도 이번 범위가 아니라
> 기존 Issue #3 에서 처리합니다.

**5~9 단계(답변 제출 이후 반복 흐름)는 Issue #24 에서 구현했습니다.** 답변 업로드
URL 발급 → 답변 제출 → AI 폴링(최대 60초) → 다음 질문·꼬리질문·되묻기·세션 종료
처리 → DB 반영 → WebSocket push 가 동작합니다. 2~4 단계(세션 시작·첫 질문 수신)는
Issue #23 에서 구현했습니다.

> **Issue #25 에서 구현했습니다:** 사용자 세션 abort API
> (`POST /api/interviews/{sessionId}/abort`), 세션 상태 역전 방지(IN_PROGRESS 에서만
> COMPLETED·ABORTED 로 전이), AI error_code 별 재시도·정리 정책. 상세 AI 계약 문서
> 정합화는 Issue #42 에서 정리합니다.
>
> 아직 구현하지 않은 것(후속): 동일 질문 중복 제출 방지·in-flight idempotency,
> 세션 시작 `LLM_FAILED` 의 session_id 유지 재시도(현재는 AI 내부 재시도 실패 시
> 바로 정리). WebSocket 핸드셰이크 인증·연결 전 push 유실은 Issue #3/#35 범위입니다.

---

## 세션 시작 파라미터

| 필드 | 필수 | 값 |
|---|---|---|
| `resume_file_url` | ✅ | Presigned GET URL (만료 15분) |
| `job_role` | ✅ | 자유 문자열. VARCHAR(100) |
| `persona` | ✅ | `friendly` (순한맛) \| `pressure` (매운맛) — `Persona` enum |
| `company_id` | — | Backend `company.company_id`(BIGINT PK)를 문자열로 변환한 값. AI 는 로그·추적용으로만 사용. 미선택이면 null |
| `company_profile_override` | 기업 선택 시 필수 | 선택된 기업의 인재상. `CompanyProfileFormatter` 로 조립 |
| `question_count` | ❌ | 3 \| 6 \| 9. 기본 6 |
| `retry_of_session_id` | ❌ | 재연습이면 최초 세션 ID |
| `replay_log` | ❌ | 재연습이면 필수. [`12-replay.md`](./12-replay.md) |

**Front 는 AI 의 `company_id` 를 직접 넘기지 않고, Backend `company` 테이블의
`company_id`(PK)를 넘깁니다.** Backend 는 그 값을 문자열로 변환해 AI 에 로그·추적용
`company_id` 로 전달하며, AI 는 이 ID 를 기업 조회 key 로 사용하지 않습니다. 실제
질문 생성용 기업 정보는 `company_profile_override` 로 전달합니다. `company_id` 는
optional 이며(값이 없으면 null 로 전송, JSON 에서는 키가 빠짐), AI 서버는 기업
목록을 갖지 않습니다(`GET /ai/companies` 없음). 기업 데이터의 Source of Truth 는
Backend 이며 `verified=false` 기업은 조회 자체에서 제외합니다.

**`session.document_id` 는 NOT NULL 입니다.** AI 세션 시작에 `resume_file_url` 이
필수라 문서 없는 세션은 성립하지 않습니다. 면접 자료는 기존 `PORTFOLIO` 문서
타입을 그대로 쓰며, 별도 `RESUME` 타입은 추가하지 않습니다.

> 위 표는 Backend → AI(`POST /ai/sessions`)로 나가는 필드입니다. **프론트 → Backend
> REST 요청(`POST /api/interviews`)의 문서 필드명은 `documentId`** 입니다(Issue #46,
> 문서 API 와 통일. 이전 이름 `documentPublicId` 는 더 이상 쓰지 않습니다). 값은 문서의
> `public_id`(UUID 문자열)이며 필수입니다. Backend 가 이 문서로 `resume_file_url`
> presigned GET 을 발급해 AI 에 넘깁니다.

> **진행 방식 선택은 프론트 → Backend REST 요청 전용입니다.** `POST /api/interviews`
> 요청 본문에 선택값 `hideQuestionText`(boolean)를 받습니다. `true` 면 질문 텍스트를
> 숨기고 음성만 들려주는 실전 모드로 세션을 저장합니다(`session.hide_question_text`).
> 생략하면 기본값은 텍스트+음성(`false`)입니다. 이 값은 질문 생성에 영향을 주지 않고
> 표시 방식만 바꾸므로 AI 세션 시작 요청에는 포함하지 않습니다.

> **답변 제한 시간도 프론트 → Backend REST 요청 전용입니다(Issue #99).** `POST /api/interviews`
> 요청 본문에 선택값 `answerTimeLimitSec` 를 받습니다. 허용값은 `60`·`90`·`120`·`null`
> 이며(프론트 프리셋과 일치), `null` 은 "제한 없음"을 뜻합니다. 그 외 값은 AI 호출·세션 저장
> 전에 `INVALID_REQUEST`(400)로 거부합니다(`question_count` 화이트리스트와 같은 방식). 저장
> 컬럼은 기존 `session.duration_limit`(nullable)을 그대로 씁니다(스키마 변경 없음).
>
> **필드 누락과 명시적 `null` 을 구분합니다(Issue #99 R3).** 프론트 화면 기본 선택값이 90초라,
> 필드를 보내지 않으면(누락) 백엔드가 기본값 **90초**를 적용해 저장하고, 사용자가 직접 고른
> **"제한 없음"**(명시적 `null`)은 그대로 `null` 로 저장합니다. record + `Integer` 만으로는 두
> 경우가 Jackson 역직렬화상 구별되지 않으므로, 요청 역직렬화 경계에서 3상태 래퍼
> `AnswerTimeLimit`(ABSENT/UNLIMITED/값)로 받아 구분합니다(새 의존성 없이 Jackson 의
> absent/null 콜백만 사용). 응답·엔티티·DB 는 그대로 `Integer`(제한 없음이면 `null`)를 쓰며
> 기존 계약을 바꾸지 않습니다. 기존 `duration_limit=null` 세션도 그대로 제한 없음으로
> 해석합니다. 타이머는 UI 표시 설정이라 AI 세션 시작 요청에는 포함하지 않으며, 서버가 타이머를
> 직접 실행하거나 자동 제출하지도 않습니다(저장·반환만 담당).

> **시작 응답에 확정 옵션이 함께 내려갑니다(Issue #99).** `POST /api/interviews` 응답
> (`InterviewStartResponse`)은 기존 `sessionId`·`questionTotal`·`replayApplied` 에 더해, **실제
> 저장된 세션의** `hideQuestionText`·`interviewerStyle`·`answerTimeLimitSec` 를 함께 돌려줍니다.
> 요청값이 아니라 저장값 기준이라 응답과 DB 가 어긋나지 않습니다. `interviewerStyle` 은 프론트
> 계약에 맞춰 소문자(`friendly`·`pressure`)로 내려갑니다(엔티티 enum 은 대문자, `Persona.toAiValue()`
> 로 변환). 기존 필드는 그대로라 이 필드들을 읽지 않는 기존 클라이언트는 영향받지 않습니다
> (필드 추가뿐, 하위 호환).

### 세션 옵션 조회 — `GET /api/interviews/{sessionId}` (Issue #99)

진행 중이거나 완료된 세션의 **현재 진행 옵션**을 조회합니다. 프론트 면접 화면이 시작 시 보낸
옵션을 자체 보관하지 않아도(새 탭·새로고침·다른 기기) 서버 저장값을 진실 소스로 읽기 위한
API 입니다. **AI 서버를 호출하지 않고** 저장된 세션만 읽습니다.

| 항목 | 값 |
|---|---|
| 인증 | `Authorization: Bearer <accessToken>` 필요. 본인 세션만 |
| 없음/남의 세션 | `SESSION_NOT_FOUND`(404) |
| 완료 상태 제약 | **없음.** 진행 중(IN_PROGRESS)·완료·중단 모두 조회 가능 |
| 재면접 세션 | 최초 세션이 아니라 **그 회차 세션 자신**의 옵션(변경된 숨김·타이머 포함) |

응답(`Result` 공통 포맷의 `data`):

```json
{
  "sessionId": "sess_example",
  "hideQuestionText": true,
  "interviewerStyle": "pressure",
  "answerTimeLimitSec": 90
}
```

- `interviewerStyle` 은 소문자(`friendly`·`pressure`).
- `answerTimeLimitSec` 은 `60`·`90`·`120` 중 하나이거나, 제한 없음이면 `null`. **타이머 저장이
  연결되기 전(Issue #99 이전) 생성된 과거 세션은 저장된 값이 없어 `null`(제한 없음)로 내려갑니다.**
  과거 데이터는 실제 설정값을 알 수 없어 마이그레이션하지 않습니다.
- 이 API 는 **현재 세션의 진행 옵션** 조회입니다. 재연습 시작을 위한 **최초 세션의 고정 옵션**은
  목적이 다른 `GET /api/interviews/{sessionId}/replay-options`([`12-replay.md`](./12-replay.md))를
  쓰세요. 두 API 는 공존하며 서로 대체하지 않습니다.


토픽 수는 보내지 않습니다. `question_count` 에서 자동 결정됩니다.

```
3문항 → 2토픽    6문항 → 3토픽    9문항 → 4토픽
```

---

## 질문 유형

### 주질문 (`question`)

토픽의 시작점. 문항 수에 포함됩니다.

### 꼬리질문 (`followup`)

직전 답변의 허점을 파고드는 질문. 답변을 듣고 그때 생성되므로 미리 만들 수 없습니다.
문항 수에 포함됩니다.

**답변 제출 후 5~15초 대기가 발생합니다.** 프론트에 로딩 표시가 필요합니다.

### 되묻기 (`reask`)

답변이 부실할 때 같은 질문을 다시 요청하는 것.

- **문항 수에 포함되지 않습니다.** `question_number` 가 올라가지 않습니다
- `category`, `difficulty` 가 null입니다
- `is_spare_topic`, `is_replay` 는 항상 false입니다
- 문구는 고정이 아니며 답변에서 무엇이 빠졌는지에 따라 매번 다릅니다

**한도**

```
토픽당   1회. 되묻고도 부실하면 그 토픽의 꼬리질문을 포기하고 다음으로
세션당   3회. 넘으면 되묻지 않고 바로 다음으로
```

### 예비 토픽 질문 (`is_spare_topic: true`)

꼬리질문이 생략되어 문항 수가 모자랄 때 채우기용으로 투입되는 토픽의 질문.
정상 동작이며 사용자에게는 일반 질문과 구분되지 않습니다.

### 질문 WebSocket 페이로드 (프론트 관점)

새 질문은 `type: "question"` 으로 push 되며, 음성 관련 두 필드가 함께 옵니다.

| 필드 | 의미 |
|---|---|
| `audioUrl` | 재생 가능한 질문 음성이 있으면 Backend 가 발급한 **presigned GET URL**. 음성이 없거나 URL 발급에 실패하면 `null` |
| `audioAvailable` | 현재 재생 가능한 질문 음성 URL 이 있는지 여부 |

- `audioAvailable=true` 면 `audioUrl` 로 바로 재생할 수 있습니다(만료 15분).
- `audioAvailable=false` (`audioUrl=null`) 면 **텍스트만으로 진행**합니다. TTS 가 없거나
  음성 URL 발급이 실패한 경우이며, 세션은 계속됩니다. 프론트는 음성 없이 질문 텍스트를
  보여주면 됩니다.
- 주질문·꼬리질문 모두 같은 규칙입니다. 저장·경로·발급 정책은
  [`20-storage.md`](./20-storage.md), AI 계약은 [`10-ai-client.md`](./10-ai-client.md).

---

## 카테고리

8개 고정. 가운뎃점(·)까지 정확히 일치해야 합니다.

```
지원동기  직무역량  프로젝트경험  문제해결
협업·갈등  실패·성장  가치관·인성  미래계획
```

Java enum으로 만들지 않습니다. 이유는 [`02-database.md`](./02-database.md).

## 난이도

`L1` ~ `L3`. 난이도 배분은 AI 내부 로직이며 Spring이 관여하지 않습니다.

---

## ★ 진행률 표시

```
권장     "질문 4 / 9"     question_number / question_total
비권장   "주제 2 / 4"     topic_total 이 세션마다 달라짐
```

**문항 수는 사용자가 선택한 값이 그대로 지켜집니다.** 답변이 부실해 꼬리질문이
생략되면 예비 토픽을 투입해 채우기 때문입니다.

**토픽 수는 세션마다 다릅니다.** 부실하게 답할수록 토픽이 늘어납니다.
`topic_index`, `topic_total` 은 참고용이며 진행률에 쓰지 않습니다.

되묻기가 나가도 `question_number` 는 올라가지 않으므로 진행률이 뒤로 가거나
멈춘 것처럼 보이지 않습니다. 다만 사용자 입장에서는 아무 변화가 없어 보일 수 있으니
프론트에 "다시 답변해 주세요" 같은 별도 표시가 필요합니다.

---

## 세션 상태

| 상태 | 진입 조건 |
|---|---|
| `in_progress` | 세션 생성 + AI 응답 수신 |
| `completed` | `session_end` 수신 |
| `aborted` | 사용자 중단 / 타임아웃 / 복구 불가 오류 |

`aborted` 세션은 리포트를 생성하지 않습니다.

사용자가 중간에 나가 세션을 정리하는 **abort API `POST /api/interviews/{sessionId}/abort`
는 Issue #25(PR #43)에서 구현했습니다.** 소유자 세션을 조회해 AI abort 를 best-effort
로 호출하고 Backend 세션을 `ABORTED` 로 정리합니다. **AI abort 호출이 실패해도 로컬
`ABORTED` 정리는 반드시 수행**해, AI 서버가 죽어도 세션이 `IN_PROGRESS` 로 영구
잔류하지 않습니다.

- 이미 `ABORTED` 인 세션 재중단은 멱등 no-op
- `COMPLETED` 세션 중단은 `SESSION_ENDED` 로 거부(완료를 중단으로 역전하지 않음)
- 상태 전이는 `IN_PROGRESS` 일 때만 일어나, abort 후 늦게 도착한 폴링 결과가 세션을
  완료로 되돌리거나 새 질문을 저장·push 하지 않습니다
- 폴링 타임아웃·복구 불가 오류 등 Backend 내부 정리 경로에서도 `aborted` 로 전이합니다

---

## 저장 시점

| 이벤트 | 저장할 것 |
|---|---|
| 질문 수신 (`question`/`followup`/`reask`) | 질문 로그 전체 |
| 답변 업로드 완료 | `answer_audio_object_key`·`answer_video_object_key`(카메라 미사용 시 null)·`answer_is_timeout`. 답변 제출 시점에 저장 (Issue #24) |
| `session_end` 수신 | `status = completed` |

**질문은 수신 즉시 저장합니다.** 프론트에 push한 뒤에 저장하면, 사용자가 그 사이에
브라우저를 닫았을 때 유실됩니다. 이 로그가 리포트와 재연습의 유일한 근거입니다.

`session_end` 의 `total_questions` 는 되묻기를 제외한 실제 질문 수입니다.

---

## 다시보기 (`GET /api/interviews/{sessionId}/recordings`, Issue #103)

연습 기록에서 세션의 「다시보기」를 누르면 부릅니다. 이 저장소에서 `replay` 는 재연습이라
다시보기에는 `recordings` 를 씁니다.

**면접 전체를 담은 영상 파일은 없습니다.** 면접 시작 때 녹화를 시작하지 않고, 답변마다 프론트가
오디오(필수) · 영상(선택)을 따로 올리기 때문입니다. 그래서 파일을 합치지 않고 문항별 재생목록을
내려주고, 프론트가 순서대로 이어서 재생합니다.

```json
{
  "sessionId": "sess_9f2a1c",
  "hasVideo": true,
  "items": [
    { "questionId": "q_1", "questionNumber": 1, "type": "QUESTION", "questionText": "지원 동기를 말씀해 주세요.",
      "questionAudioUrl": "https://...", "answerAudioUrl": "https://...", "answerVideoUrl": "https://..." }
  ]
}
```

- **완료(`COMPLETED`) 세션만.** 진행 중은 409 `SESSION_NOT_COMPLETED`, 중단은 409 `SESSION_ABORTED`,
  없거나 남의 세션은 404 `SESSION_NOT_FOUND`. 리포트 요청과 같은 분기입니다
- 조립 규칙은 리포트 요청(`ReportRequestAssembler`)과 같습니다. 나간 순서대로(되묻기 포함),
  답변 녹음이 없는 질문은 빼고, 영상 key 가 없으면 `answerVideoUrl` null
- 영상이 하나도 없으면 오류가 아니라 200 에 `hasVideo: false` 입니다
- `questionAudioUrl` 은 질문 push 와 같이 계약 key 로 서명하고, 음성이 없거나(TTS 실패) 서명이
  실패하면 null 입니다
- 질문 텍스트를 숨긴 면접이어도 `questionText` 를 내려줍니다
- **URL 은 매 요청마다 새로 서명하고 저장하지 않습니다.** 셋 다 1시간이며, 파일은 지우지 않으므로
  만료되면 다시 부르면 됩니다. 만료 기준은 [`20-storage.md`](./20-storage.md)
