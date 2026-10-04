# PARTIAL 리포트 실패 축 재시도

> 관련 이슈: (생성 전)
> 작성일: 2026-10-04
> 선행: #66 리포트 상세 조회 (`AiReportResultReader` 로 `overall.axes_failed` 를 읽음)

## 배경 / 목표

말하기·시선 축이 실패한 `PARTIAL` 리포트에서 **실패한 축만 다시 분석**하는 API 를 만든다.
지금은 PARTIAL 이 끝 상태라 사용자가 빠진 점수를 받을 방법이 없다. 등록 API 재요청은
FAILED 만 받는다. AI 는 요청한 축만 다시 계산하므로 전체를 다시 돌리는 것보다 싸다
(시선만 재시도하면 LLM 요금이 없고, 말하기만 재시도하면 GPU 를 쓰지 않음).

**재시도 중에도 리포트 화면은 그대로 둔다.** 리포트는 PARTIAL 로 계속 조회되고, 다시 분석 중인
축의 칸에서만 로딩을 보여준다. 그래서 리포트 상태(`status`)와 재시도 상태를 따로 둔다.

**완료 기준**

- `POST /api/reports/{reportId}/retry` 한 번으로 202 를 받고, 진행·결과는 기존
  `WS /ws/reports/{reportId}` 로 받는다
- 재시도 중에도 상세 조회가 200 으로 PARTIAL 리포트를 주고, `retry.status = PROCESSING` 으로 재시도 중임을 알린다
- 성공하면 리포트가 새 결과로 통째로 바뀐다 (PARTIAL → COMPLETED 또는 다시 PARTIAL)
- 실패해도 원래 PARTIAL 리포트를 잃지 않고, 실패 이유가 DB 에 남아 새로고침해도 보인다

## 범위

**포함**

- 재시도 API (동기 구간)
- `report.retry_status` 컬럼과 상세 조회 · 상태 조회 응답의 `retry` 필드
- AI 클라이언트 `POST /ai/sessions/{sessionId}/report/retry` 호출과 목 응답
- 폴러가 재시도 task 를 폴링하고 결과를 저장. 자동 재시도 1회도 재시도 엔드포인트로
- 문서 갱신 (`13-report.md` · `10-ai-client.md` · `02-database.md` · `90-open-questions.md`)

**제외**

- FAILED 복구. 기존처럼 등록 API(`POST /api/interviews/{sessionId}/reports`) 재요청으로 한다
- 사용자가 축을 고르는 기능. 실패한 축 전부(`axes_failed`)를 그대로 보낸다 (계약 14장)
- 재시도 횟수 상한. AI 파트와 논의 후 결정 (아래 결정 필요 사항)
- 회차 비교, 고아 PROCESSING 정리

## 영향 범위 / 관련 도메인

| 위치 | 변경 | 이유 |
|---|---|---|
| `domain/report/entity/Report` · `ReportRetryStatus`(신규 enum) | `retryStatus` 필드, 재시도 시작·성공·실패 메서드 | 리포트 상태와 재시도 상태 분리 |
| `domain/report/repository/ReportRepository` | 재시도 시작 조건부 UPDATE | 동시 재시도 중 하나만 통과 |
| `domain/report/service/ReportRetryService` (신규) | 동기 구간 | `ReportRequestService` 와 진입 조건이 달라 분리 |
| `domain/report/service/ReportWriter` | `startRetry` · `failRetry` 추가, `finish` 가 `retryStatus` 를 비움 | DB 쓰기는 짧은 트랜잭션으로 |
| `domain/report/service/ReportRequestedEvent` | `axes` 추가 (있으면 재시도) | 폴러의 자동 재시도가 어느 엔드포인트를 부를지 알아야 함 |
| `domain/report/service/ReportPoller` | 재시도 task 폴링, 실패 처리 분기 | 폴링·저장은 생성과 같고 실패 처리만 다름 |
| `domain/report/service/ReportStatusService` · `ReportQueryService` | 응답에 `retry` 추가, 재시도 중 stage · progress | 화면이 재시도 칸 로딩을 그릴 수 있게 |
| `domain/report/dto/response` | `ReportRetryInfo`(공통), `ReportRetryResponse` | |
| `infrastructure/ai/AiClient` · `RealAiClient` · `MockAiClient` | `retryReport` 추가 | AI 호출은 클라이언트를 통해서만 |
| `infrastructure/ai/dto/AiReportRetryRequest` (신규) | 생성 본문 + `axes` | 계약 14장 |
| `common/exception/ErrorCode` | `REPORT_NOT_RETRYABLE`, `REPORT_RETRY_IN_PROGRESS` | |

**재사용**

- `ReportRequestAssembler.build(sessionId)`: 생성 본문 그대로. presigned URL 도 여기서 새로 발급된다
- `ReportRequestService.idempotencyKey(sessionId, attempt)`: 키 규칙과 시도 번호를 생성과 공유
- `AiReportResultReader`(#66): 저장된 `report_data` 에서 `overall.axes_failed` 를 읽음
- `ReportPoller` 의 폴링 · 진행 단계 push · 저장 전 형식 확인 · `ReportFailurePolicy`
- `ReportProgressStore`(Redis): 재시도 중 stage · progress 도 같은 키에 둔다

의존 방향은 기존 리포트 흐름과 같다(도메인 → `infrastructure/ai`). 새 방향은 없다.

## API 변경

### `POST /api/reports/{reportId}/retry` (신규)

요청 본문 없음.

```json
202 { "success": true, "data": { "reportId": "...", "status": "PARTIAL",
      "retry": { "status": "PROCESSING", "axes": ["gaze"], "errorCode": null, "message": null, "retryable": null } } }
```

| 단계 | 내용 | 실패 시 |
|---|---|---|
| ① | 본인 리포트 조회 | 404 `REPORT_NOT_FOUND` |
| ② | `status == PARTIAL` 인지 | 409 `REPORT_NOT_RETRYABLE` (PROCESSING · COMPLETED · FAILED) |
| ③ | `retry_status != PROCESSING` 인지 | 409 `REPORT_RETRY_IN_PROGRESS` |
| ④ | `report_data.overall.axes_failed` 읽기 | 비어 있으면 409 `REPORT_NOT_RETRYABLE`. AI 의 400 을 미리 막음 |
| ⑤ | AI 재시도 요청 (Idempotency-Key `rpt_{sessionId}_{attempt+1}`) | 503 · 504. **행을 바꾸지 않음** |
| ⑥ | 조건부 UPDATE (`PARTIAL` & `attempt` 일치 & 재시도 중 아님) → `retry_status = PROCESSING`, `attempt+1`, 새 task | 0행이면 409 `REPORT_RETRY_IN_PROGRESS` (동시 요청이 먼저) |
| ⑦ | 커밋 후 폴링 시작 이벤트, 202 | |

- **AI 호출이 UPDATE 보다 먼저**다. 등록 API 와 같은 이유로, AI 가 거절하면 리포트를 그대로 둔다.
  동시 재시도는 같은 키로 AI 를 부르므로 AI 작업은 하나고, ⑥에서 한쪽만 통과한다
- 호출 제한: 등록 API 와 같은 분당 10회

### 상세 조회 · 상태 조회 응답에 `retry` 추가 (프론트 계약 변경)

```json
"retry": { "status": "PROCESSING", "axes": ["gaze"], "errorCode": null, "message": null, "retryable": null }
```

| `retry` | 의미 |
|---|---|
| `null` | 재시도한 적 없음, 또는 재시도가 성공해 결과가 교체됨 |
| `status: PROCESSING` | 재시도 중. `axes` 칸에 로딩 표시 |
| `status: FAILED` | 마지막 재시도 실패. `errorCode` · `message` · `retryable` 은 리포트 실패와 같은 규칙 |

- `axes` 는 재시도 대상 축이며 `overall.axesFailed` 와 같다
- 상태 조회는 재시도 중이면 `status: PARTIAL` 과 함께 `stage` · `progress` 도 준다 (지금은 PROCESSING 일 때만)
- 상세 조회는 재시도 중에도 200 이다. 기존 필드 모양은 바뀌지 않고 `retry` 만 추가된다

### WebSocket

같은 `/ws/reports/{reportId}` 를 쓴다. 메시지 종류는 그대로다.

- `progress`: 재시도 진행 단계
- `report`: 재시도 성공. 프론트는 상세 조회를 다시 부른다
- `error`: 재시도 실패. 리포트는 PARTIAL 그대로

## DB 변경

`report` 에 컬럼 하나 추가 (`docs/02-database.md`).

```sql
retry_status    VARCHAR(20)   NULL    -- null | PROCESSING | FAILED. 실패 축 재시도 상태
```

- `ReportRetryStatus` 는 우리 상태라 enum 이다 (AI 응답 필드가 아님)
- nullable 이라 `ddl-auto: update` 로 기존 행에 문제없이 추가된다

| 시점 | `status` | `retry_status` | `report_data` · 점수 | `error_code` | `attempt` · `ai_task_id` |
|---|---|---|---|---|---|
| 재시도 시작 | PARTIAL 유지 | PROCESSING | 그대로 | null | +1 · 새 task |
| 재시도 성공 | 새 결과 (COMPLETED · PARTIAL) | null | 통째로 교체 | null | 그대로 |
| 재시도 실패 | PARTIAL 유지 | FAILED | 그대로 | 실패 코드 | 그대로 |

- **`error_code` 를 재시도 실패에도 쓴다.** `status = FAILED` 일 때는 리포트 실패 원인,
  `retry_status = FAILED` 일 때는 재시도 실패 원인이다. 두 경우가 동시에 생기지 않는다
- `completed_at` 은 재시도 중에도 그대로 둔다 (리포트는 여전히 끝난 상태). 성공하면 새 시각

## AI 서버 연동

- `POST /ai/sessions/{session_id}/report/retry`, 헤더 `Idempotency-Key: rpt_{session_id}_{새 시도번호}`
- 본문: 생성 본문(`persona` · `job_role` · `company_id` · `company_profile_override` · `answers`) + `axes`
- 응답 `202 { task_id }`. 폴링(`GET /ai/tasks/{task_id}`)은 생성과 같고 결과도 **전체 리포트**
- 타임아웃 · 폴링 주기는 생성과 같음 (`app.report.poll-timeout` 10분)
- 자동 재시도(`CONTENT_FAILED` · `MEDIA_FETCH_FAILED` 1회)도 **재시도 엔드포인트**로 같은 `axes` 를
  다시 보낸다. 이벤트에 `axes` 가 있으면 재시도, 없으면 생성
- 저장 전 형식 확인(`AiReportResultReader.validate`)도 그대로 탄다. 못 읽으면 재시도 실패로 처리
- AI 호출은 트랜잭션 밖(`ReportRetryService` 에 `@Transactional` 없음), DB 쓰기만 `ReportWriter`
- 목: `MockAiClient.retryReport` 는 요청 축을 다시 계산한 전체 리포트를 돌려준다. 실패 트리거는
  생성과 같은 URL 마커 규칙

## 구현 계획

1. [x] `AiReportRetryRequest`, `AiClient.retryReport`, `RealAiClient` · `MockAiClient` 구현과 테스트
2. [x] `ReportRetryStatus` enum, `Report.retryStatus` 와 시작·성공·실패 메서드, 조건부 UPDATE, `ReportWriter.startRetry` · `failRetry`
3. [x] `ReportRequestedEvent` 에 `axes` 추가, `ReportPoller` 가 생성/재시도를 나눠 자동 재시도하고 실패를 나눠 처리
4. [x] `ReportRetryService` + 컨트롤러 엔드포인트, 동기 구간 테스트 (404 · 409 두 종류 · 동시 요청 · AI 실패 시 행 유지)
5. [x] 상세 조회 · 상태 조회 응답에 `retry` 추가, 상태 조회가 재시도 중 stage · progress 를 붙임
6. [x] 문서: `13-report.md`(재시도 절, "아직 없는 것" 정리), `10-ai-client.md`(재시도 엔드포인트),
   `02-database.md`(`retry_status`), `90-open-questions.md`(재시도 횟수 상한)

## 결정 필요 사항 / 리스크

1. **재시도 횟수 상한 (AI 파트와 논의)**. 계약에는 없다. 시선 재시도는 GPU 를 오래 쓰므로 무한 재시도는
   AI 서버 비용 위험이다. 정해지기 전까지는 호출 제한(분당 10회)만 두고, 상한이 정해지면 ②에 조건 하나를
   더한다. `90-open-questions.md` 에 항목으로 올린다
2. **재시도 실패 문구를 리포트 실패와 같게 둘지.** `ReportFailurePolicy` 를 그대로 쓰면 "답변 녹음을
   가져오지 못했습니다" 같은 문구가 축 칸에 나온다. 칸 안에 맞는 짧은 문구가 필요하면 프론트와 정한다
3. 리스크: 서버 재시작으로 폴링이 끊기면 `retry_status = PROCESSING` 이 남아 재시도가 409 로 막힌다.
   리포트 자체는 PARTIAL 로 계속 보인다. 기존 고아 PROCESSING 정리 작업에서 함께 풀어야 한다
4. 리스크: 시도 번호(`attempt`)를 생성과 함께 센다. "생성 시도 수"가 아니라 "AI 작업 수"라는 점을 문서에 남긴다
5. 리스크: #66 이 먼저 머지돼야 한다 (`AiReportResultReader`, 상세 조회 응답)
6. `docs/13-report.md` "아직 없는 것" 에 "만들지 팀 확인 필요"로 남아 있던 항목이다. 이번에 만들기로 했으므로 문서에서 정리한다
