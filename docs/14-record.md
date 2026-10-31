# 14. 연습 기록

연습 기록 화면은 **세션 목록**입니다. 행마다 리포트 요약을 붙여, 누르면 그 세션의 리포트로 갑니다.

세션과 리포트를 함께 보여주므로 `record` 도메인에 둡니다. interview 에 두면 report 를 거꾸로
불러야 해서 순환 의존이 생깁니다(report 는 이미 interview 를 씁니다). `record` 는 리포지토리 없이
두 도메인의 서비스(`InterviewSessionQueryService` · `ReportSummaryQueryService`)만 부릅니다.

---

## 목록 (`GET /api/practice-records`)

```
GET /api/practice-records?status=COMPLETED&folderId=...&keyword=삼성&page=0&size=20
```

```json
{
  "records": [
    { "sessionId": "sess_9f2a1c", "companyName": "삼성전자", "jobRole": "마케팅", "persona": "PRESSURE",
      "questionCount": 9, "status": "COMPLETED", "round": 1, "createdAt": "2026-08-02T10:00:00Z",
      "report": { "reportId": "3f1c...", "status": "COMPLETED", "scoreTotal": 84 } }
  ],
  "page": 0, "size": 20, "totalElements": 12, "totalPages": 1
}
```

| 파라미터 | 설명 |
|---|---|
| `status` | `COMPLETED` · `IN_PROGRESS`. 없으면 둘 다. 그 외 값은 400 `INVALID_REQUEST` |
| `folderId` | 그 폴더의 세션만. 남의 폴더면 빈 목록. UUID 가 아니면 400 |
| `keyword` | 회사명 · 직무 부분 일치, 대소문자 무시. `%` · `_` 는 글자 그대로 찾음 |
| `page` · `size` | 문서 목록과 같은 규칙(`PageRequests`). 0부터, 기본 20 · 최대 100 |

- **중단(`ABORTED`) 세션은 나오지 않습니다.** 리포트도 없고 이어서 할 수도 없습니다
- 최신순(`createdAt` 내림차순, 같으면 `sessionId`)
- `companyName` 은 회사를 고르지 않았으면 null
- `report` 는 리포트를 요청하지 않았으면 null. `scoreTotal` 은 분석 중이거나 실패면 null.
  결과 원본(`report_data`)은 읽지 않고 컬럼에서 꺼냅니다
- 쿼리는 페이지 크기와 상관없이 세 번입니다: 세션(회사 fetch) · 회차 · 리포트 요약

### 회차 (`round`)

- 1회차(루트)는 1, 재연습은 **같은 루트에서 자기보다 먼저 시작한 재연습 수 + 2** 입니다
- **중단된 재연습은 세지 않습니다.** 2회차를 중단하고 다시 하면 그게 2회차입니다
- 목록을 읽은 직후 세션이 중단돼 회차 조회에서 빠져도, 앞선 재연습 수로 세므로 목록이 깨지지 않습니다
- 루트 추적은 `retry_of_session_id`(항상 최초 세션)를 씁니다. [`12-replay.md`](./12-replay.md)

---

## 디자인과 다른 점 · 아직 없는 것

| 항목 | 상태 |
|---|---|
| 「중립적 면접관」 | persona 는 `FRIENDLY` · `PRESSURE` 둘뿐. 디자인 확인 필요 |
| 「이어하기」 | 기능 없음 |
| 「영상 만료됨」 | 보관 기간 정책 없음. 답변 영상은 지우지 않고 보관됩니다 |
| 폴더 칩 · 세션 개수 | 폴더 API 몫(별도 이슈) |
