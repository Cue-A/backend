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

## 조립 로직

```java
public List<ReplayLogItem> buildReplayLog(String rootSessionId) {
    return questionLogRepository
            .findBySessionIdOrderByCreatedAt(rootSessionId).stream()
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

정렬 기준은 `created_at` 입니다. `question_number` 는 되묻기 때문에 중복될 수
있으므로 정렬 키로 쓰지 않습니다.

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
