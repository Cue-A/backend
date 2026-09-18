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
9. session_end 수신 → status = completed → 리포트 작업 시작
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

> 아직 구현하지 않은 것(후속): 동일 질문 중복 제출 방지·in-flight idempotency,
> AI error_code 별 재시도 정책, 사용자 세션 abort API 는 Issue #25 범위입니다.
> WebSocket 핸드셰이크 인증·연결 전 push 유실은 Issue #3 범위입니다.

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
PK(문서 없이 연습 모드면 없음)를 넘깁니다.** Backend 는 그 PK 를 문자열로 변환해
AI 에 로그·추적용 `company_id` 로 넘기고, 질문 생성용 인재상은
`company_profile_override` 로 조립해 보냅니다. AI 는 `company_id` 로 기업 데이터를
조회하지 않으며, AI 서버는 기업 목록을 갖지 않습니다(`GET /ai/companies` 없음).
기업 데이터의 Source of Truth 는 Backend 이며 `verified=false` 기업은 조회
자체에서 제외합니다.

**`session.document_id` 는 NOT NULL 입니다.** AI 세션 시작에 `resume_file_url` 이
필수라 문서 없는 세션은 성립하지 않습니다. 면접 자료는 기존 `PORTFOLIO` 문서
타입을 그대로 쓰며, 별도 `RESUME` 타입은 추가하지 않습니다.

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

사용자가 중간에 나가면 `POST /ai/sessions/{id}/abort` 를 호출해 AI 쪽 상태도
정리합니다.

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
