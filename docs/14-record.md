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
      "folder": { "folderId": "7c9e...", "folderName": "삼성전자" },
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
- `folder` 는 폴더에 넣지 않았으면 null. 행 메뉴에 지금 폴더를 체크하고, 「불러오기」 모달에 지금 폴더를
  보여주는 데 씁니다
- `report` 는 리포트를 요청하지 않았으면 null. `scoreTotal` 은 분석 중이거나 실패면 null.
  결과 원본(`report_data`)은 읽지 않고 컬럼에서 꺼냅니다
- 쿼리는 페이지 크기와 상관없이 세 번입니다: 세션(회사 · 폴더 fetch) · 회차 · 리포트 요약

### 회차 (`round`)

- 1회차(루트)는 1, 재연습은 **같은 루트에서 자기보다 먼저 시작한 재연습 수 + 2** 입니다
- **중단된 재연습은 세지 않습니다.** 2회차를 중단하고 다시 하면 그게 2회차입니다
- 목록을 읽은 직후 세션이 중단돼 회차 조회에서 빠져도, 앞선 재연습 수로 세므로 목록이 깨지지 않습니다
- 루트 추적은 `retry_of_session_id`(항상 최초 세션)를 씁니다. [`12-replay.md`](./12-replay.md)

---

## 폴더 (`/api/folders`)

화면 위쪽의 폴더 카드입니다. `Folder` 엔티티가 interview 도메인에 있어 API 도 interview 에 둡니다
(`FolderController` · `FolderService`).

| API | 설명 |
|---|---|
| `GET /api/folders` | 폴더 목록과 세션 수. `totalCount` 는 「전체」 카드 |
| `POST /api/folders` | 만들기. 201 |
| `PATCH /api/folders/{folderId}` | 이름 바꾸기. 지금 이름과 같으면 그대로 성공 |
| `DELETE /api/folders/{folderId}` | 지우기. 안의 세션은 남고 폴더에서만 빠짐 |

```json
{
  "totalCount": 12,
  "folders": [
    { "folderId": "7c9e...", "folderName": "삼성전자", "sessionCount": 5, "createdAt": "2026-07-01T10:00:00Z" }
  ]
}
```

- 이름은 앞뒤 공백을 자른 뒤 1~50자(`folder_name VARCHAR(50)`). 저장 · 중복 비교도 자른 값으로 합니다
- 내 폴더끼리 같은 이름이면 409 `FOLDER_NAME_DUPLICATED`, 20개를 넘으면 409 `FOLDER_LIMIT_EXCEEDED`.
  문서 상한처럼 애플리케이션에서만 검사해, 같은 요청이 동시에 두 번 오면 둘 다 통과할 수 있습니다
- 없는 폴더 · 남의 폴더 · UUID 가 아닌 값은 404 `FOLDER_NOT_FOUND`
- 목록은 만든 순서(`createdAt`, 같으면 PK)
- **세션 수는 연습 기록 목록과 같은 기준**입니다(`SessionStatus.LISTED`). 회차마다 하나로 세고 중단된
  세션은 세지 않아, `totalCount` 가 필터 없는 목록의 `totalElements` 와 같습니다
- 폴더에 넣지 않은 세션은 「전체」에만 보입니다. 디자인의 「기타」도 사용자가 만든 일반 폴더입니다
- 지우면 세션의 `folder_id` 는 DB 의 `ON DELETE SET NULL` 이 비웁니다. 연습 기록이 폴더 정리 한 번에
  날아가면 안 됩니다([`02-database.md`](./02-database.md) 폴더 절)

### 폴더 옮기기 (`PATCH /api/practice-records/folder`)

```json
{ "sessionIds": ["sess_9f2a1c", "sess_71bd02"], "folderId": "7c9e..." }
→ { "folderId": "7c9e...", "movedSessionIds": ["sess_9f2a1c", "sess_1a2b3c", "sess_71bd02"] }
```

행 맨 오른쪽 버튼(1개)과 폴더 안 「불러오기」 모달(여러 개)이 이 API 하나를 같이 씁니다.

- **면접 단위로 움직입니다.** 보낸 회차가 속한 면접의 1회차와 재연습이 모두 같은 폴더로 갑니다. 그래서
  `movedSessionIds` 가 보낸 것보다 많을 수 있습니다. 루트는 `retry_of_session_id` 로 찾습니다
- 세션은 폴더 하나에만 들어갑니다. 다른 폴더에 있던 세션은 그 폴더에서 빠집니다
- `folderId` 가 null 이면 폴더에서 뺍니다. 키가 없어도 null 로 봅니다
- `sessionIds` 는 중복을 빼고 1~100개(연습 기록 한 페이지의 최대 크기). 넘으면 400 `INVALID_REQUEST`
- **전부 옮기거나 전부 실패합니다.** 하나라도 없거나 남의 것이거나 중단된 세션이면 404 `SESSION_NOT_FOUND`,
  폴더가 없거나 남의 것이면 404 `FOLDER_NOT_FOUND`
- 중단된 세션은 목록에 없으므로 같은 면접이어도 옮기지 않습니다
- 쿼리는 세 번입니다: 폴더 · 보낸 세션 · 그 면접 전체. 바뀐 세션은 커밋 때 함께 저장됩니다

---

## 디자인과 다른 점 · 아직 없는 것

| 항목 | 상태 |
|---|---|
| 「중립적 면접관」 | persona 는 `FRIENDLY` · `PRESSURE` 둘뿐. 디자인 확인 필요 |
| 「이어하기」 | 기능 없음 |
| 「영상 만료됨」 | 보관 기간 정책 없음. 답변 영상은 지우지 않고 보관됩니다 |
| 재면접이 1회차 폴더 물려받기 | 재면접 세션 생성(#78)이 머지된 뒤 후속 작업. 그전에는 새 재면접이 폴더 없이 시작합니다 |
