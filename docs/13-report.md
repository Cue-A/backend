# 13. 리포트 · 성장 추적

리포트 계약(「리포트생성 API계약」)이 도착해 **생성 흐름**을 반영했습니다(Issue #47).
조회·부분 재시도·회차 비교는 아직입니다. 아래 "아직 없는 것" 참고.

---

## 평가 3축

```
content   내용    관련성 · 구체성 · 논리성
speech    말하기  속도 · 머뭇거림 · 침묵 · 마무리
gaze      시선    응시 유지 · 회피 빈도
```

점수는 0~100(내부), 표시는 1~5 입니다. AI 가 둘 다 주므로 Backend 는 변환하지 않습니다.
DB 에는 0~100 점수만 컬럼으로 꺼내고, 나머지는 원본(`report_data`)에 둡니다.

### ★ 부분 실패

내용 관련성이 **총점 게이트**라 내용 축만 필수입니다. 주제에서 벗어난 유창한 답변이
말하기·시선 점수만으로 높은 총점을 받는 것을 막습니다.

| 축 | 실패 시 | AI 응답 | `report.status` |
|---|---|---|---|
| **content** | **전체 실패** | 폴링 `status: error`, `CONTENT_FAILED` | `FAILED` |
| speech | 그 축만 null | `done` + `report_status: partial` | `PARTIAL` |
| gaze | 그 축만 null | `done` + `report_status: partial` | `PARTIAL` |
| gaze (카메라 미사용) | 실패 아님 | `done` + `complete`, gaze `skipped` | `COMPLETED` |

프론트는 `failed`(분석 실패)와 `skipped`(카메라 미사용)를 다르게 표시합니다.

---

## 생성 흐름

```
POST /api/interviews/{sessionId}/reports     → 202 { reportId, sessionId, status, createdAt }
WS   /ws/reports/{reportId}                  → progress · report · error
```

### 동기 구간 (HTTP 요청 스레드, 1초 이내)

| 단계 | 내용 | 실패 시 |
|---|---|---|
| ① | 본인 세션 조회 | 404 `SESSION_NOT_FOUND` |
| ② | 세션이 `COMPLETED` 인지 | `IN_PROGRESS` 409 `SESSION_NOT_COMPLETED`, `ABORTED` 409 `SESSION_ABORTED` |
| ③ | 기존 리포트 확인 | 없으면 시도 1, `FAILED` 면 다음 시도, 그 외 409 `REPORT_ALREADY_EXISTS` |
| ④ | AI 에 리포트 요청 (Idempotency-Key `rpt_{sessionId}_{attempt}`) | 422 `REPORT_TOO_SHORT` · 503 · 504. **행을 만들지 않음** |
| ⑤ | 리포트 행 저장 `PROCESSING` | 동시 요청이 먼저 저장했으면 409 `REPORT_ALREADY_EXISTS` |
| ⑥ | 202 | |

**AI 호출이 저장보다 먼저입니다.** AI 등록이 실패하면 행을 남기지 않기 위해서입니다.
대신 동시 요청은 Idempotency-Key 가 막습니다. 같은 세션의 두 요청은 같은 키로 AI 를
부르므로 AI 가 같은 task_id 를 주고, 저장 단계(`session_id` UNIQUE 또는 `FAILED` 조건부
갱신)에서 한쪽만 성공합니다. AI 작업이 두 번 돌지 않습니다.

`FAILED` 재요청은 새 행이 아니라 **같은 행을 되돌립니다.** `reportId` 가 그대로입니다.

### 백그라운드 (커밋 후 시작, 최대 10분)

`ReportPoller` 가 리포트 행이 커밋된 뒤(`@TransactionalEventListener(AFTER_COMMIT)`)
가상 스레드에서 폴링합니다. 커밋 전에 시작하면 아직 저장되지 않은 행을 못 찾습니다.

| AI 결과 | 처리 |
|---|---|
| `processing` | stage 가 바뀔 때마다 WS `progress` |
| `done` + `complete` | `COMPLETED`, 점수·원본 저장, WS `report` |
| `done` + `partial` | `PARTIAL`, 실패 축 점수 null, WS `report` |
| `CONTENT_FAILED` · `MEDIA_FETCH_FAILED` | **새 키로 자동 재시도 1회.** 또 실패하면 `FAILED` |
| `STT_FAILED` · 그 외 | `FAILED`, WS `error` |
| 10분 초과 | `FAILED` (`AI_TIMEOUT`) |

자동 재시도는 요청 본문을 다시 조립해 presigned URL 도 새로 받습니다
(`MEDIA_FETCH_FAILED` 는 URL 만료가 원인일 수 있음).

어떤 경로로 끝나든 `PROCESSING` 으로 남기지 않습니다. 저장 중 예기치 못한 오류도
`FAILED`(`INTERNAL_ERROR`)로 정리합니다.

### 요청 본문 (`ReportRequestAssembler`)

- `persona` · `job_role` · `company_id` · `company_profile_override` 는 **면접 시작 때와 같은 값**
- `answers[]` 는 질문을 나간 순서대로(`created_at`), 되묻기 포함
- 녹음·영상은 object key 로 발급한 presigned GET(1시간). 영상이 없으면 `video_url: null`
- **답변 녹음이 없는 질문은 뺍니다.** 빈 URL 을 보내면 AI 가 `MEDIA_FETCH_FAILED` 로
  리포트 전체를 실패시킵니다

### WebSocket 메시지

```json
{ "type": "progress", "payload": { "stage": "ANALYZING_CONTENT", "progress": 0.6 } }
{ "type": "report",   "payload": { "reportId": "...", "status": "COMPLETED", "scoreTotal": 68 } }
{ "type": "error",    "payload": { "errorCode": "STT_FAILED", "message": "...", "retryable": false } }
```

| AI stage | `stage` |
|---|---|
| `transcribing` | `TRANSCRIBING` |
| `analyzing_speech` | `ANALYZING_SPEECH` |
| `analyzing_gaze` | `ANALYZING_GAZE` |
| `analyzing_content` | `ANALYZING_CONTENT` |
| `composing` | `COMPOSING` |

`retryable` 이 true 면 등록 API 로 다시 요청할 수 있습니다.

| errorCode | retryable |
|---|---|
| `CONTENT_FAILED` · `MEDIA_FETCH_FAILED` · `AI_TIMEOUT` · `AI_UNAVAILABLE` | true |
| `STT_FAILED` · 그 외 | false |

면접 소켓(`/ws/interviews/{sessionId}`)과 따로 둡니다. 리포트는 면접이 끝난 뒤라 면접
소켓은 닫혀 있을 가능성이 높습니다.

---

## 알아둘 제약

- **서버가 재시작되면 폴링이 끊깁니다.** 그 리포트는 `PROCESSING` 으로 남고 재요청도
  409 로 막힙니다. 오래된 `PROCESSING` 을 정리하는 작업이 필요합니다(후속)
- **소켓에 늦게 붙으면 완료 메시지를 놓칩니다.** 상태 조회 API 로 따라잡습니다(Issue #48)
- AI 서버는 단일 인스턴스라 재배포하면 task 가 사라집니다. 폴링이 `SESSION_NOT_FOUND`
  로 끝나 `FAILED` 가 되고, 사용자가 다시 요청하면 됩니다

---

## 아직 없는 것

| 기능 | 비고 |
|---|---|
| 리포트 조회 API | `report_data` 원본을 DTO 로 옮겨 내보냄 |
| 상태 조회 API | Issue #48 |
| 실패한 축만 재시도 (`/ai/sessions/{id}/report/retry`) | `PARTIAL` 전용. 만들지 팀 확인 필요 |
| 회차 비교 (`/ai/reports/compare`) | 전체 회차의 `report_data` 를 함께 보냄 |

### 회차 비교 규칙 (계약 8장)

- 비교 대상은 `is_replay == true` 인 질문뿐입니다. `is_spare_topic` 으로 거르지 않습니다.
  [`12-replay.md`](./12-replay.md)
- 점수 비교는 **직전 회차 기준**, 회차 수 제한 없음
- `PARTIAL` 회차는 총점 비교에서 빠지고(스케일이 다름) 성공한 축만 비교합니다
