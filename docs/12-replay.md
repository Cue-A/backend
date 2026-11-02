# 12. 재연습

재연습은 **1회차와 같은 질문 세트로 다시 면접을 보는 것**입니다.
같은 질문에 대한 답변 변화를 비교해야 성장 추적이 의미를 갖습니다.

---

## 재현 규칙

| 대상 | 재현 여부 |
|---|---|
| 주질문 | **텍스트까지 그대로 재사용.** 예비 토픽 주질문도 포함 |
| 토픽별 꼬리질문 개수 | 1회차와 동일하게 고정 |
| 꼬리질문 내용 | **매번 다름.** 새 답변을 읽고 생성 |
| 되묻기 | 재현하지 않음. 그때그때 답변에 따라 발생 |

토픽 구조가 같아야 회차 비교가 정확해집니다. 1회차에 꼬리질문 2개를 받았던 주제에서
2회차에 5개를 받으면, 점수 차이가 실력 변화인지 질문 구성 변화인지 알 수 없습니다.

### 2회차에도 부실하게 답한 경우

되묻기 후에도 부실하면 그 토픽의 남은 꼬리질문을 포기하고, 대체 주질문으로 문항 수를
채웁니다. 대체 질문은 1회차에 없던 것이므로 **회차 비교 대상이 아닙니다.**

---

## AI 요청 형식

```json
POST /ai/sessions
{
  "resume_file_url": "https://.../resume_abc.pdf",
  "job_role": "백엔드 개발",
  "persona": "pressure",
  "company_id": "hyundai_enc",
  "question_count": 6,
  "retry_of_session_id": "sess_1st",

  "replay_log": [
    { "type": "question", "text": "백엔드 개발 직무에 지원하신 이유를 말씀해 주세요.",
      "category": "지원동기", "difficulty": "L1", "is_spare_topic": false },
    { "type": "question", "text": "가장 자신 있는 기술 스택은 무엇인가요?",
      "category": "직무역량", "difficulty": "L1", "is_spare_topic": false },
    { "type": "followup", "difficulty": "L2" },
    { "type": "question", "text": "팀원과 의견이 갈렸던 경험을 말씀해 주세요.",
      "category": "협업·갈등", "difficulty": "L2", "is_spare_topic": false },
    { "type": "followup", "difficulty": "L3" },
    { "type": "followup", "difficulty": "L3" }
  ]
}
```

### 담을 필드

| 필드 | question | followup |
|---|---|---|
| `type` | 필수 | 필수 |
| `text` | 필수 | **불필요** |
| `category` | 필수 | 불필요 |
| `difficulty` | 필수 | **필수** |
| `is_spare_topic` | 필수 | 불필요 |

- **꼬리질문에도 `difficulty` 를 반드시 담습니다.** 빠뜨리면 난이도가 달라져
  점수 변화가 실력 때문인지 난이도 때문인지 구분할 수 없습니다
- `reask` 는 배열에서 제외합니다
- `question_number`, `topic_index`, `audio_url` 은 보내지 않습니다
- `category` 는 8개 값 중 하나여야 합니다. 다르면 `INVALID_CATEGORY`

### 진실 소스

**재연습의 진실 소스는 백엔드 DB입니다.** AI는 1회차 로그를 영속 저장하지 않으며,
Spring이 요청에 함께 보냅니다. AI 세션은 종료 시 정리되므로 `retry_of_session_id`
만으로는 며칠 뒤 재연습을 할 수 없습니다.

---

## ★ 루트 세션 추적

`retry_of_session_id` 는 **직전 회차가 아니라 항상 최초 세션**을 가리킵니다.

```
2회차   retry_of_session_id = 1회차 ID
3회차   retry_of_session_id = 1회차 ID   ← 2회차 ID 아님
4회차   retry_of_session_id = 1회차 ID
```

### 구현

```java
// 잘못됨 — 3회차부터 조용히 어긋난다
String rootId = sourceSession.getId();

// 올바름
String rootId = sourceSession.getRetryOfSessionId() != null
        ? sourceSession.getRetryOfSessionId()
        : sourceSession.getId();
```

`replay_log` 도 루트 세션의 로그로 조립합니다. 직전 회차 로그를 쓰면 안 됩니다.

### 왜 고정하는가

직전 회차 기준이면 대체 질문이 정식 질문으로 승격되어 회차마다 문항이 불어납니다.

```
1회차   Q1 Q2 Q3
2회차   Q1 Q2 Q3 + 대체 Q4        부실한 답변으로 대체 질문 발생
3회차   Q1 Q2 Q3 Q4               2회차 기준이면 대체 Q4가 정식 질문이 됨
4회차   Q1 Q2 Q3 Q4 + 대체 Q5
```

1회차와 4회차를 비교할 수 없고, 2회차와 4회차도 구성이 다릅니다.

1회차로 고정하면 모든 회차가 같은 질문을 받습니다.

```
1회차   Q1 Q2 Q3
2회차   Q1 Q2 Q3
3회차   Q1 Q2 Q3
4회차   Q1 Q2 Q3
```

고정해야 오히려 비교가 자유로워집니다. 직전 회차 비교, 1회차 대비 비교,
전체 추이가 모두 가능해집니다.

> **질문 세트를 1회차로 고정하는 것**과 **점수를 직전 회차와 비교하는 것**은
> 다른 문제입니다. 전자는 세션 구성, 후자는 리포트 영역입니다.
> 회차 수 제한은 없습니다.

---

## 옵션 고정 정책 (Issue #26 구현, 팀 확정)

재면접은 **최초(root) 세션의 주요 옵션을 그대로 사용**합니다. 같은 주질문 세트를 다시
출제하고 꼬리질문만 새로 생성해 회차별 결과를 비교하는 것이 재면접의 핵심이라, 질문
생성에 영향을 주는 옵션이 달라지면 재면접의 의미가 없어지기 때문입니다.

**재면접 요청(`sourceSessionId` 포함)에서 고정 옵션이 최초 세션과 하나라도 다르면
`INVALID_REQUEST`(400)로 요청을 거부합니다.** 이전처럼 일반 면접으로 조용히 전환하지
않습니다. 3회차 이상에서도 **항상 최초 root 세션의 옵션**을 기준으로 비교합니다.

| 옵션 | 다르면 | 비고 |
|---|---|---|
| `questionCount` 문항 수 | **400 거부** | 고정 |
| `persona` 면접관 스타일 | **400 거부** | 고정 |
| `documentId` 이력서 | **400 거부** | 고정 |
| `companyId` 기업 | **400 거부** | 고정 |
| `jobRole` 직무 | **400 거부** | 고정 |
| `hideQuestionText` 질문 텍스트 숨김 | **허용** | 표시 방식만 바꿈 |
| 타이머 | **허용** | 세션마다 저장(`duration_limit`)하지만 질문 구성에 무관해 고정하지 않음 |

### AI 계약과 Backend 서비스 정책의 구분

- **AI 계약**은 그대로입니다. `POST /ai/sessions` 재사용, `retry_of_session_id` 는 항상 최초
  root 세션 ID, `replay_log` 는 root 질문 로그, REASK 제외, question/followup 필드 구조 유지,
  `is_replay` 는 AI 응답값 저장. AI 요청 JSON·DTO 는 바꾸지 않습니다.
- AI 계약 자체에는 "문항 수·페르소나가 바뀌면 일반 면접으로 처리" 규칙이 있지만, 이번
  정책은 그 상황을 **Backend 가 사전에 400 으로 거부**하는 서비스 정책입니다. 즉 불일치
  요청은 애초에 AI 로 나가지 않습니다.

### 구현 메모

- 비교는 **요청 원문 문자열이 아니라 Backend 가 이미 조회·정규화한 값**으로 합니다.
  문서는 조회된 `Document` 의 `public_id`(UUID)로 비교해, 대소문자만 다른 UUID 가 다른
  문서로 오판되는 것을 막습니다. `questionCount` 는 일반 면접과 같은 규칙(null → 6)으로
  정규화한 뒤 비교합니다. `hideQuestionText`·타이머는 비교하지 않습니다.
- 옵션 불일치 시 **AI 호출·새 세션 생성·재면접 연관 저장을 모두 하지 않습니다.**
- 사용자 메시지 예: "재연습은 최초 면접과 같은 조건으로만 시작할 수 있습니다. 이력서이(가)
  최초 면접과 다릅니다."
- root 질문 로그가 비었거나 필수 필드가 누락된 경우는 저장 데이터 무결성 문제이므로
  `INTERNAL_ERROR`(500)이며, 상세 원인은 서버 로그로만 남기고 사용자에게는 일반 메시지를
  돌려줍니다.

### 응답의 `replayApplied`

세션 시작 응답(`InterviewStartResponse`)에는 **실제 재연습이 적용됐는지**를 알려주는
`replayApplied`(boolean) 필드가 있습니다.

| 상황 | `replayApplied` |
|---|---|
| 일반 면접 성공(`sourceSessionId` 없음) | `false` |
| 정상 재연습 성공 | `true` |
| 재연습 고정 옵션 불일치 | 400 오류 — **정상 응답 없음** |

- `replayApplied` 는 **재연습 적용 여부를 확인하는 결과 필드**일 뿐입니다. 최초 세션의
  옵션을 조회하거나 프론트에서 옵션을 고정하는 용도가 아닙니다.
- 재연습용 세션 옵션 조회 API 는 **별도 이슈(Issue #83)로 구현**됐습니다. 아래
  "재연습용 세션 옵션 조회 API" 절 참고.

### 프론트 연동

프론트는 재면접 시작 전에 **최초 세션의 고정 옵션을 불러와 그대로 고정**해 보내야 합니다.
사용자가 옵션을 바꾸면 400 으로 거부되기 때문입니다. 이를 위한 **재연습용 세션 옵션 조회
API 는 Issue #83 으로 구현**됐습니다(아래 절). 프론트가 그 응답으로 옵션을 자동 채우고
변경 불가로 잠그는 UI 는 **아직 별도 작업**이며, 그 전까지는 프론트가 가진 최초 세션
정보로 옵션을 채웁니다.

---

## 재연습용 세션 옵션 조회 API (Issue #83 구현)

재면접을 시작하기 전에 **최초(root) 세션의 고정 옵션**을 불러오는 조회 API 입니다.
프론트는 이 값을 자동으로 채운 뒤 재면접을 시작합니다.

```
GET /api/interviews/{sessionId}/replay-options
```

- **인증 필요**(`@CurrentUser`). 공통 응답 `Result<T>` 로 감쌉니다.
- 경로의 `sessionId` 에는 **어느 회차(1·2·3…)의 세션 ID 를 넣어도** 됩니다. 응답은 항상
  그 묶음의 **최초 root 세션** 옵션입니다(2·3회차 ID 로 조회해도 1회차 값).
- **AI 서버를 호출하지 않습니다.** 백엔드 DB 에 저장된 세션 정보만 읽습니다(재연습의
  진실 소스는 백엔드 DB).

### 응답 필드 (10개)

| 필드 | 타입 | 설명 |
|---|---|---|
| `rootSessionId` | string | 최초 root 세션 ID |
| `documentId` | string(UUID) | 이력서 문서 식별자(`public_id`). document 는 NOT NULL 이라 항상 값 있음 |
| `companyId` | number\|null | 기업 PK. 기업 미선택 연습 모드면 `null` |
| `jobRole` | string | 직무 |
| `questionCount` | number | 문항 수(DB 저장 정규화 값, 3·6·9) |
| `persona` | string | `FRIENDLY`\|`PRESSURE` (재면접 시작 요청과 같은 형식) |
| `documentTitle` | string | 이력서 제목(표시용) |
| `companyName` | string\|null | 기업명(표시용). 기업 미선택이면 `null` |
| `replayable` | boolean | **지금 이 조건으로 재연습을 시작할 수 있는지(참고 정보).** 최종 판정은 시작 API |
| `replayBlockReasons` | array | 재연습 불가 사유 목록. 가능하면 `[]`. 각 원소는 `{ code, message }` |

고정 옵션 5개(`documentId`·`companyId`·`jobRole`·`questionCount`·`persona`)를 그대로
`POST /api/interviews` 에 실으면 `ReplayOptionPolicy` 비교를 통과합니다. 타이머와
`hideQuestionText` 는 **담지 않습니다**(타이머는 세션마다 `duration_limit` 에 저장되지만
재연습에서 변경 가능해 고정 옵션이 아니고, `hideQuestionText` 도 변경 허용 옵션이라 고정
옵션이 아님).

`replayable`·`replayBlockReasons` 는 Issue #83 리뷰에서 추가된 필드입니다. 기존 8개 필드의
이름·타입·의미는 그대로이며 뒤에 덧붙였을 뿐이라 **하위 호환됩니다.**

### 예외

| 상황 | 결과 |
|---|---|
| 없는 세션 / 다른 사용자 세션 | `SESSION_NOT_FOUND`(404) |
| 완료되지 않은(IN_PROGRESS·ABORTED) 세션 | `SESSION_NOT_COMPLETED`(409) |
| root 가 없거나 남의 세션(참조 깨짐) | `SESSION_NOT_FOUND`(404) |
| 기업 미선택 연습 모드 | 정상 응답(`companyId`·`companyName` = null) |

**삭제된 문서·`verified=false` 기업이 연결된 과거 세션이어도 조회는 막지 않습니다.**
과거 세션 정보 조회와 실제 재면접 시작 가능 여부는 별개이며, 시작 가능 여부는 재면접
시작 API 가 최종 판정합니다(`findUsableFileDocument` → `DOCUMENT_NOT_FOUND`,
`findVerifiedCompany` → `INVALID_REQUEST`).

### 조회 가능 여부 ≠ 재연습 가능 여부

둘은 다른 축입니다. **조회 가능 여부**는 "이 세션의 과거 옵션을 볼 수 있는가"이고,
**재연습 가능 여부**는 "지금 이 옵션 그대로 시작 API 를 호출하면 통과하는가"입니다.

| 상황 | 조회(HTTP) | `replayable` | `replayBlockReasons` |
|---|---|---|---|
| 유효한 문서 + (기업 없음 \| 검증된 기업) | 200 | `true` | `[]` |
| 삭제된 문서가 연결된 세션 | 200 | `false` | `[{ "code": "DOCUMENT_DELETED", ... }]` |
| `verified=false` 기업이 연결된 세션 | 200 | `false` | `[{ "code": "COMPANY_UNVERIFIED", ... }]` |
| 삭제 문서 + 미검증 기업 동시 | 200 | `false` | 사유 2개 모두 |
| 없는/남의/미완료 세션 | 404·409 | — (응답 없음) | — |

- `replayBlockReasons` 의 `code` 는 시작 API 가 실제로 던지는 거부와 1:1 입니다
  (`DOCUMENT_DELETED` → 시작 시 `DOCUMENT_NOT_FOUND`, `COMPANY_UNVERIFIED` → 시작 시
  `INVALID_REQUEST`). **시작 API 에 없는 거부 사유를 조회에서 새로 만들지 않습니다.**
- 판정은 root 세션에 이미 fetch join 으로 로딩된 `document`·`company` 로만 하므로 **추가
  쿼리가 없습니다.**
- **재연습 옵션은 사용자가 바꿀 수 없습니다**(질문 재활용 기능의 기획 정책). 삭제된 문서나
  미검증 기업을 다른 것으로 교체해 시작하는 경로는 제공하지 않습니다. `replayable=false` 면
  프론트는 "현재 조건으로는 재연습을 시작할 수 없다"고 안내하고 시작 버튼을 비활성화합니다.
- `replayable` 은 어디까지나 **참고 정보**입니다. 조회와 시작 사이에 문서·기업 상태가 바뀔 수
  있으므로 최종 판정은 언제나 시작 API 가 합니다.

### 구현 메모

- 소유권 검증(`getOwnedSession`)·root 결정(`rootSessionId()`)·root fetch join 조회
  (`getRootForReplay`·`findRootForReplay`)는 재면접 시작 흐름의 로직을 **그대로 재사용**합니다.
  새 쿼리나 소유권 로직을 중복 구현하지 않습니다.
- root 조회는 `document`·`company` 를 fetch join 으로 초기화하므로 `open-in-view=false`
  에서도 안전하게 DTO 로 변환합니다.

---

## 조립 로직

```java
public List<ReplayLogItem> buildReplayLog(String rootSessionId) {
    return questionLogRepository
            .findBySessionIdOrderByCreatedAt(rootSessionId).stream()
            // 1차 created_at(위 쿼리) + 2차 question_number 로 재정렬(동률 안정화)
            .sorted(comparing(QuestionLog::getCreatedAt)
                    .thenComparingInt(QuestionLog::getQuestionNumber))
            .filter(log -> !log.isReask())          // reask 제외
            .map(this::toReplayItem)
            .toList();
}

private ReplayLogItem toReplayItem(QuestionLog log) {
    if (log.isFollowup()) {
        return ReplayLogItem.followup(log.getDifficulty());
    }
    return ReplayLogItem.question(
            log.getText(),
            log.getCategory(),          // 원문 그대로
            log.getDifficulty(),
            log.isSpareTopic()
    );
}
```

정렬 기준은 1차 `created_at` 오름차순, 2차 `question_number` 입니다. `created_at`
이 드물게 동률일 때 `questionId` 문자열(`"q_2"` vs `"q_10"`)로 정렬하면 숫자 순서가
깨지므로, AI 가 매긴 출제 순번(`question_number`)을 2차 키로 씁니다. `question_number`
는 되묻기(`REASK`)에서는 올라가지 않지만, REASK 는 조립에서 제외되고 보존되는
`QUESTION`·`FOLLOWUP` 의 번호는 단조 증가하므로 보존 항목의 순서가 흔들리지 않습니다.
(구현: `ReplayLogAssembler` 가 `QuestionRepository.findAllBySessionIdOrderByCreatedAtAsc`
결과를 `Comparator.comparing(createdAt).thenComparingInt(questionNumber)` 로 다시 정렬.)

---

## 회차 비교 필터

**`is_replay == true` 하나만 봅니다.** `is_spare_topic` 은 필터에 쓰지 않습니다.

| 상황 | is_spare_topic | is_replay | 비교 대상 |
|---|---|---|---|
| 1회차 예비 토픽 주질문 재사용 | true | true | ✅ |
| 2회차 대체 질문 | true | false | ❌ |
| 꼬리질문 | — | 항상 false | ❌ |
| 되묻기 | false | 항상 false | ❌ |

`is_spare_topic` 으로 거르면 첫 줄이 잘못 빠집니다.

두 필드는 축이 다릅니다.

```
is_spare_topic   계획된 토픽인가, 채우기용 토픽인가
                 세션 내부 구조 정보. 통계·디버깅용이며 화면 로직에 쓰지 않는다

is_replay        1회차와 동일한 질문인가
                 회차 간 관계 정보. 비교 화면은 이것만 본다
```

---

## 회차 목록 조회

같은 루트를 가진 세션 전부를 시간순으로 가져옵니다.

```java
// 루트 세션 자신 + 그것을 참조하는 모든 재연습
List<Session> findAllRounds(String rootId);
```

`retry_of_session_id IS NULL AND session_id = :rootId`
`OR retry_of_session_id = :rootId` 조건이며,
`idx_session_retry_root` 인덱스를 씁니다.
