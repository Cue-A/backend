# 리포트 분석 작업 등록 (비동기)

> 관련 이슈: #47 (상태 조회 API 는 #48)
> 작성일: 2026-09-26

## 배경 / 목표

면접이 끝난(`COMPLETED`) 세션의 분석을 AI 에 맡기고 **즉시 202 를 반환**하는 API 를 만든다.
분석은 최대 10분이 걸리므로 폴링은 백그라운드에서 돌고, 진행률과 결과는 WebSocket 으로 보낸다.

**완료 기준**

- `POST` 한 번으로 202 `{ reportId, sessionId, status, createdAt }` 를 1초 안에 받는다
- 백그라운드 폴링이 끝나면 `report` 행이 `COMPLETED` / `PARTIAL` / `FAILED` 중 하나가 되고
  WebSocket 으로 `report` 또는 `error` 가 나간다
- 404 · 409 세 종류 · 422 · 503 · 504 · FAILED 후 재요청이 명세대로 동작한다

## 범위

**포함**

- 분석 작업 등록 API (동기 구간 ①~⑥)
- 백그라운드 폴링 · 결과 저장 · 자동 재시도 1회 (⑦~⑪)
- 리포트 WebSocket 경로와 메시지 3종 (`progress` · `report` · `error`)
- AI 클라이언트의 리포트 생성 요청 · 리포트 task 조회
- `MockAiClient` 의 리포트 응답 (로컬 기본값이 mock 이므로 필수)
- `Report` 엔티티를 계약에 맞게 정리
- 문서 갱신 (`13-report.md` · `02-database.md` · `10-ai-client.md` · `90-open-questions.md`)

**제외**

- 리포트 조회 API (`GET .../reports/{reportId}`)
- 실패한 축만 재시도하는 API (`/report/retry`). PARTIAL 전용으로 둘지 팀 확인 후 별도 작업
- 회차 비교 (`/ai/reports/compare`), `growth` 모듈
- 소켓 늦은 연결 대비 상태 조회 API (`GET .../status`). #48 로 분리 (결정 #5)
- WebSocket 인증. 면접 소켓도 없는 상태라 별도 이슈

## 영향 범위 / 관련 도메인

| 위치 | 변경 | 이유 |
|---|---|---|
| `domain/report` | controller · service · repository · dto 신규, `Report` 엔티티 정리 | 본 작업 |
| `infrastructure/ai` | `AiClient` 메서드 2개, 리포트 DTO, `AiPoller` 일반화, `RealAiClient` 타임아웃 구분, `MockAiClient` | AI 호출은 반드시 이 계층 경유 |
| `infrastructure/websocket` | 리포트 소켓 핸들러, 리포트 진행 단계 enum, 메시지 | 면접 소켓과 키(reportId)와 단계 값이 다름 |
| `common/exception/ErrorCode` | 코드 추가 | 아래 "에러 코드" |
| `common/config` | `WebSocketConfig` 경로 등록, `AsyncConfig` 리포트 실행기 | |
| `domain/interview` | `QuestionRepository` 정렬 조회 1개 추가 (읽기만) | answers[] 조립 |

**재사용할 기존 코드**

- `AiPoller.await(...)` — 1초 간격 폴링·stage 변경 콜백·타임아웃. 리포트 결과 타입만 받을 수 있게 일반화
- `InterviewFirstQuestionPoller` — `@Async` 를 별도 빈으로 떼는 패턴, 실패를 WebSocket 으로만 알리는 패턴
- `PresignedUrlIssuer.issueRecordingDownload` — 답변 미디어 GET 1시간 (계약: 영상 30분 이상 ✓)
- `CompanyProfileFormatter.format` — 면접 시작과 같은 `company_profile_override` 조립
- `InterviewSessionRepository.findBySessionIdAndUser_UserId` — 소유자 스코프 조회

**의존 방향**: `report` → `interview`(세션·질문 조회), `company`(인재상 포맷) 를 서비스 레벨에서만
참조한다. `interview` 는 `report` 를 모른다. 반대 방향 의존은 만들지 않는다.

**PR #43 과의 충돌 회피**: #43 이 `AiErrorTranslator` 를 크게 바꾸므로, 리포트 전용 재시도·
retryable 판단은 `AiErrorTranslator` 에 넣지 않고 `report` 도메인의 정책 클래스에 둔다.

## API 변경

### 등록

```
POST /api/interviews/{sessionId}/reports
Authorization: Bearer ...
(body 없음)
```

`@CurrentUser` 필수, `@RateLimit` 적용. 응답은 `Result<T>`.

```json
202
{ "success": true,
  "data": { "reportId": "UUID", "sessionId": "sess_...", "status": "PROCESSING", "createdAt": "..." },
  "errorCode": null, "message": null }
```

| HTTP | errorCode | 상황 |
|---|---|---|
| 404 | `SESSION_NOT_FOUND` | 세션 없음 / 남의 세션 |
| 409 | `SESSION_NOT_COMPLETED` | `IN_PROGRESS` |
| 409 | `SESSION_ABORTED` | `ABORTED` (기존 코드 재사용) |
| 409 | `REPORT_ALREADY_EXISTS` | `PROCESSING` · `COMPLETED` · `PARTIAL` 행 존재, 또는 동시 요청 UNIQUE 위반 |
| 422 | `REPORT_TOO_SHORT` | 답변 2문항 미만 (되묻기 제외). **report 행 만들지 않음** |
| 503 | `AI_UNAVAILABLE` | AI 연결 실패. 행 만들지 않음 |
| 504 | `AI_TIMEOUT` | AI 등록 요청 타임아웃. 행 만들지 않음 |

### WebSocket

```
/ws/reports/{reportId}
{ "type": "...", "payload": { ... } }
```

| type | payload |
|---|---|
| `progress` | `{ "stage": "TRANSCRIBING", "progress": 0.4 }` |
| `report` | `{ "reportId", "status", "scoreTotal" }` |
| `error` | `{ "errorCode", "message", "retryable" }` |

`stage` 는 AI 값을 그대로 내보내지 않고 `ReportProgressStage`
(`TRANSCRIBING` · `ANALYZING_SPEECH` · `ANALYZING_GAZE` · `ANALYZING_CONTENT` · `COMPOSING`) 로 옮긴다.
면접용 `ProgressStage` 와 값이 겹치지 않아 별도 enum 으로 둔다.

**프론트 계약이 새로 생긴다.** 경로·메시지 형식을 프론트에 공유해야 한다.

## DB 변경

기존 `Report` 엔티티는 "계약 도착 전 초안" 이라 명세와 다르다.

| 현재 | 변경 | 비고 |
|---|---|---|
| `status VARCHAR(20)` 문자열 | `ReportStatus` enum (`PROCESSING` · `COMPLETED` · `PARTIAL` · `FAILED`), STRING 저장 | 우리 상태라 enum 가능. AI 의 `report_status` 문자열은 `report_data` 에 원본 그대로 |
| `score_vision` | `score_gaze` | 결정 #3 |
| `summary` · `growth_narrative` · `timeline` · `resilience` | 엔티티에서 제거 | 계약상 전부 `report_data` 원본에 있음. 결정 #3 |
| — | `ai_task_id VARCHAR(100)` | |
| — | `attempt INT NOT NULL` | Idempotency-Key `rpt_{sessionId}_{attempt}` |
| — | `error_code VARCHAR(50)` | FAILED 일 때 |
| — | `completed_at TIMESTAMPTZ` | |
| `report_data JSONB` | 유지 | AI `result` 원본 전체. **삭제 금지** (회차 비교에 전체 회차 전송) |
| `session_id UNIQUE` | 유지 | 세션당 1행. 동시 요청 방어선 |

- `ddl-auto: update` 는 컬럼 삭제·이름 변경을 하지 않는다. 옛 컬럼(`score_vision` · `summary` · `growth_narrative` · `timeline` · `resilience`)은 수동 `DROP` 을 PR 본문과 `02-database.md` 에 안내한다. **배포 DB 의 report 행이 없는지 먼저 확인한다**
- AI 필드를 enum 으로 바꾸지 않는다. `category` 등은 `report_data` 에 원본 그대로 남는다

### 에러 코드 추가

| 코드 | HTTP | 용도 |
|---|---|---|
| `SESSION_NOT_COMPLETED` | 409 | 동기 ② |
| `REPORT_ALREADY_EXISTS` | 409 | 동기 ③⑤ |
| `REPORT_TOO_SHORT` | 422 | AI 422 를 그대로 옮김 |
| `INVALID_ANSWERS` | 400 | AI 가 answers[] 형식 오류를 줄 때. 우리 조립 버그 |
| `CONTENT_FAILED` · `MEDIA_FETCH_FAILED` | 500 | 폴링 `status:error` 코드. 없으면 `AI_UNAVAILABLE` 로 뭉개져 재시도 분기를 못 함 |

`AiErrorTranslator.toErrorCode` 가 `ErrorCode.valueOf(aiCode)` 로 옮기므로 **이름이 AI 코드와 같아야** 한다.

## AI 서버 연동

**호출**

```
POST /ai/sessions/{session_id}/report
X-Cueanda-Secret: ...            (RestClient 기본 헤더, 이미 구현)
Idempotency-Key: rpt_{sessionId}_{attempt}
→ 202 { "task_id": "..." }

GET /ai/tasks/{task_id}          1초 간격, 타임아웃 10분
```

**`AiClient` 추가**

- `String requestReport(String sessionId, String idempotencyKey, AiReportRequest request)`
- `AiReportTaskStatusResponse getReportTask(String taskId)` — 같은 GET 경로, 다른 결과 타입

현재 `AiTaskStatusResponse.result` 가 `AiQuestionResult` 로 고정이라 리포트 결과를 담지 못한다.
리포트 DTO 는 `status` · `stage` · `progress` · `result(JsonNode)` · `errorCode` · `message` 로 두고,
`result` 는 해석하지 않고 원본 그대로 `report_data` 에 저장한다. 점수 4개만 경로로 꺼낸다.

**`AiPoller` 일반화** — 두 응답 DTO 가 공통 인터페이스(`isDone` · `isFailed` · `stage` · `errorCode` · `message`)를
구현하게 하고, `await(taskId, timeout, fetcher, onProgress)` 를 추가한다. 기존 시그니처는
`aiClient::getTask` 를 넘기는 위임으로 남겨 면접 코드는 건드리지 않는다.
리포트는 `progress` 값도 필요하므로 onProgress 에 응답 객체를 넘기는 오버로드로 받는다.

**요청 body 조립** (`ReportRequestAssembler`, 재시도에서도 재사용)

| 필드 | 출처 |
|---|---|
| `persona` | `session.persona.toAiValue()` |
| `job_role` | `session.jobRole` |
| `company_id` | 회사 선택 시 `String.valueOf(company.companyId)`, 아니면 null (면접 시작과 동일) |
| `company_profile_override` | `CompanyProfileFormatter.format(company)`, 아니면 null |
| `answers[]` | 질문 행을 **생성 순서대로**, 되묻기 포함. 답변 오디오가 없는 행은 제외 (결정 #6) |
| `answers[].question_id` · `type` · `text` · `category` · `difficulty` · `question_number` · `reask_of` · `is_replay` · `is_spare_topic` | question 행. `type` 은 소문자 AI 값으로 |
| `answers[].audio_url` · `video_url` | object key 로 presigned GET 1시간. 영상 없으면 null |
| `answers[].is_timeout` | `answer_is_timeout` |

**타임아웃 구분** — 지금 `RealAiClient.call` 은 `ResourceAccessException` 을 전부 503 으로 바꾼다.
원인이 `SocketTimeoutException` 이면 504 `AI_TIMEOUT` 으로 나눈다. 면접 호출에도 같이 적용되며,
읽기 타임아웃이 503 이 아니라 504 로 바뀐다 (의미상 맞는 쪽).

**트랜잭션** — AI 호출(④)은 트랜잭션 밖. 조회(①~③)와 저장(⑤)만 짧은 트랜잭션.
서비스 클래스에는 `@Transactional` 을 붙이지 않고 `ReportWriter` 로 위임한다 (`InterviewStartService` 와 같은 구조).

## 처리 흐름 상세

### 동기 구간

```
① session = findBySessionIdAndUser_UserId           없으면 404
② session.status: IN_PROGRESS → 409 / ABORTED → 409
③ existing = reportRepository.findBySession
     없음                      → attempt = 1, 신규
     FAILED                    → attempt = existing.attempt + 1, 재사용
     그 외                     → 409 REPORT_ALREADY_EXISTS
④ taskId = aiClient.requestReport(sid, "rpt_{sid}_{attempt}", assembler.build(session))
     422 / 503 / 504 → 그대로 전파. 행 만들지 않음
⑤ ReportWriter (트랜잭션)
     신규: insert PROCESSING. UNIQUE 위반 → 409
     재사용: "status = FAILED and attempt = 이전값" 조건부 갱신. 0행이면 409
     커밋 직전 ReportRequestedEvent 발행
⑥ 202
```

**동시 요청 두 가지**

- 신규 두 번: 둘 다 ③ 통과 → 같은 키 `rpt_{sid}_1` 로 AI 호출 → AI 가 **같은 task_id** 반환 (멱등) →
  한쪽만 insert 성공, 다른 쪽 UNIQUE 위반 → 409. AI 작업은 하나뿐이라 낭비가 없다
- FAILED 재요청 두 번: 조건부 갱신이라 한쪽만 성공, 다른 쪽 409. 역시 같은 키라 AI 작업은 하나

### 백그라운드 (`ReportPoller`, `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`)

```
⑦ aiPoller.await(taskId, 10분, getReportTask, onProgress)
⑧ stage 가 바뀔 때마다 WS progress
⑨ done  + report_status=complete → COMPLETED
   done  + report_status=partial  → PARTIAL (실패 축 점수 null)
   error CONTENT_FAILED           → 자동 재시도 1회 (attempt+1, 새 키, 요청 다시 조립)
   error MEDIA_FETCH_FAILED       → 자동 재시도 1회 (presigned URL 새로 발급됨)
   error STT_FAILED · 그 외        → FAILED
   10분 초과                       → FAILED (AI_TIMEOUT)
⑩ 점수 4개 + report_data 원본 + completed_at 저장 / FAILED 면 error_code
⑪ WS report 또는 error
```

- **같은 키로 재시도하면 AI 가 실패한 기존 task_id 를 돌려준다.** 재시도는 반드시 attempt 를 먼저 올려 저장한 뒤 새 키로 보낸다
- 자동 재시도에도 10분 타임아웃을 새로 건다
- 저장·push 단계의 예외도 잡아 FAILED 로 정리한다. `@Async void` 에서 새면 PROCESSING 으로 영구 잔류한다
- 서버 재시작으로 폴링이 끊긴 PROCESSING 행은 이번 범위에서 복구하지 않는다 (리스크 참고)

**retryable** (`ReportFailurePolicy`)

| errorCode | 자동 재시도 | WS `retryable` |
|---|---|---|
| `CONTENT_FAILED` | 1회 | true |
| `MEDIA_FETCH_FAILED` | 1회 | true |
| `AI_TIMEOUT` · `AI_UNAVAILABLE` | 없음 | true |
| `STT_FAILED` | 없음 | false |
| 그 외 (`INVALID_ANSWERS` 등) | 없음 | false |

## 구현 계획

PR 이 400줄을 넘을 것이라 둘로 나눈다.

**PR 1 — AI 계층 준비** (`ai` 스코프)

1. [ ] `ErrorCode` 에 리포트·AI 코드 추가
2. [ ] 리포트 요청·응답 DTO (`AiReportRequest` · `AiReportAnswer` · `AiReportTaskStatusResponse`)
3. [ ] 태스크 상태 공통 인터페이스 + `AiPoller` 일반화 (기존 호출부 무변경)
4. [ ] `AiClient.requestReport` · `getReportTask` — `RealAiClient` (Idempotency-Key 헤더), `MockAiClient` (고정 complete 리포트)
5. [ ] `RealAiClient.call` 에서 타임아웃을 504 로 구분
6. [ ] 테스트: `AiPoller` 리포트 경로, 에러 코드 매핑, Mock 응답

**PR 2 — 리포트 도메인** (`report` 스코프)

7. [ ] `Report` 엔티티 정리 + `ReportStatus` enum + `ReportRepository` (소유자 스코프, `Repository` 상속)
8. [ ] `QuestionRepository` 정렬 조회 + `ReportRequestAssembler`
9. [ ] `ReportWriter` (신규 insert / FAILED 조건부 갱신 / 결과 저장) + 이벤트 발행
10. [ ] `ReportRequestService` + `ReportController` (①~⑥, UNIQUE 위반 → 409)
11. [ ] `ReportProgressStage` · 메시지 3종 · `ReportSocketHandler` · `WebSocketConfig` 등록
12. [ ] `ReportPoller` (⑦~⑪) + `ReportFailurePolicy` + `AsyncConfig` 리포트 실행기
13. [ ] 테스트: 서비스 단위 (404 · 409×3 · 422 · 503 · 504 · 정상 202 · FAILED 재요청), 폴러 (complete · partial · error 재시도 · 재시도 후 실패 · 타임아웃), `@WebMvcTest` 컨트롤러
14. [ ] 문서: `13-report.md` 재작성, `02-database.md` 리포트 스키마, `10-ai-client.md` 리포트 절, `90-open-questions.md` 리포트 계약 절 해결 처리
15. [ ] 수동 검증: 더미 서버 `DUMMY_POLL_TICKS=3` 으로 processing 분기 확인

## 결정 필요 사항 / 리스크

### 결정 사항

| # | 내용 | 결정 |
|---|---|---|
| 1 | 경로 | **확정.** `POST /api/interviews/{sessionId}/reports`, WS `/ws/reports/{reportId}`. 명세의 `/v1/...` · `/ws/api/...` 는 쓰지 않음 |
| 2 | 응답 봉투 | **확정.** 컨벤션대로 `Result<T>` `{ success, data, errorCode, message }`. 명세 쪽을 고침 |
| 3 | 기존 `report` 테이블 컬럼 | **확정.** `score_vision` → `score_gaze`, JSONB 4개 엔티티에서 제거, 옛 컬럼은 수동 `DROP` 안내 |
| 4 | 재시도 API (`/report/retry`) | **확정.** 이번 범위 제외. 만든다면 PARTIAL 전용 |
| 5 | 늦은 소켓 연결 대비 상태 조회 API | **확정.** #48 로 분리, 나중에 작업 |
| 6 | 답변 오디오가 없는 질문 행 | **확정.** answers[] 에서 제외하고 로그. 넣으면 AI 가 `MEDIA_FETCH_FAILED` 로 전체 실패 |
| 7 | WS `progress` 에 진행률(0~1) | **확정.** `{ "stage", "progress" }` 로 보냄 |

`docs/90-open-questions.md` 의 **"리포트 계약" 절은 "도착 전까지 엔티티를 만들지 말라"** 로 남아 있다.
계약서는 이미 도착했으므로 이 작업에서 해결 처리한다. 단, 계약서 11장의 미확정 항목
(`metrics` 세부 필드, 축 가중치, 게이트 임계값, `resilience` 산출식)은 **값의 문제라 구조에 영향이 없다**.
원본을 `report_data` 에 통째로 저장하므로 이 작업을 막지 않는다.

### 리스크

- **서버 재시작 시 PROCESSING 고아.** 폴링이 메모리에서 돌아 재시작하면 끊긴다. 그 행은 영원히
  PROCESSING 이고 재요청도 409 로 막힌다. 후속으로 "오래된 PROCESSING 을 FAILED 로 돌리는 정리"
  또는 "기동 시 재개" 가 필요하다. 이번엔 문서에 남기고 넘어간다
- **AI 서버 단일 인스턴스 제약.** 재배포하면 task 가 사라져 폴링이 404 `SESSION_NOT_FOUND` 를 받는다.
  FAILED + retryable 로 처리한다
- **WebSocket 인증 없음.** reportId 가 UUID 라 추측은 어렵지만, 알면 남의 진행 상황을 받을 수 있다. 면접 소켓과 같은 수준
- **PR #43 충돌.** `AiErrorTranslator` 는 건드리지 않는다. `RealAiClient` · `ErrorCode` 는 #43 이 안 건드려 충돌 가능성 낮음
- **`AiErrorTranslator` 의 `valueOf` 매핑.** AI 가 우리에게 없는 코드를 주면 `AI_UNAVAILABLE`(retryable) 로 뭉개진다. 계약서 9장 코드를 전부 `ErrorCode` 에 넣어 막는다
