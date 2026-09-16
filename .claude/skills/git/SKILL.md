---
name: git
description: docs/01-conventions.md를 참고해 커밋, 브랜치, 이슈, PR 등 git 관련 액션을 수행하는 스킬.
---

# Git 액션 워크플로우

git 관련 작업 전에 이 워크플로우를 따른다.

## Step 1 — 컨벤션 읽기

반드시 `docs/01-conventions.md`의 "작업 흐름 — 이슈 → 브랜치 → PR" 절부터 읽고 시작한다.

```
Read: docs/01-conventions.md
```

## Step 2 — 작업 유형 파악 및 실행

요청 내용에 따라 아래 액션을 수행한다.

### 커밋

- 형식: `{타입}({스코프}): {요약}`
- 타입: `feat` / `fix` / `refactor` / `docs` / `test` / `chore`
- 스코프는 **건드린 곳**이다. 도메인이나 계층 이름을 쓴다.

  ```
  도메인   auth  user  document  interview  report  growth  company
  계층     common  ai  websocket  storage  redis  security  config
  기타     build  docker  github  domain  guide
  ```

- 요약은 한글, 마침표 없음
- **스코프를 하나로 못 고르겠으면 PR을 쪼개라는 신호다.** 저장소 전체에 걸치는 변경(빌드 설정 등)만 스코프를 생략한다.
- 본문이 필요하면 **무엇을 했는지보다 왜 했는지**를 쓴다. 무엇을 했는지는 diff를 보면 된다.
- `Co-Authored-By`, `Claude-Session`, `Generated with` 같은 AI 트레일러를 넣지 않는다
- 작업 단위별로 커밋을 나눈다

```
feat(interview): 재연습 세션 생성 API 추가
fix(ai): 세션 시작 타임아웃을 90초로 분리
refactor(replay): 루트 세션 추적을 ReplayService 로 이동
```

### 이슈 생성

- **모든 작업은 이슈에서 시작한다. 이슈 없이 브랜치를 따지 않는다.**
- 템플릿을 쓴다 — 기능·개선은 `.github/ISSUE_TEMPLATE/feature.md`(제목 `[feat] `), 버그는 `.github/ISSUE_TEMPLATE/bug.md`(제목 `[fix] `)
- 작업 상세 내용을 체크리스트로 작성한다
- `docs/90-open-questions.md`에 걸리는 항목이 있는지 확인하고 이슈에 남긴다

### 브랜치 생성

- 형식: `{타입}/{이슈번호}-{영문-요약}` — 예: `feat/12-interview-session`
- **이슈번호에 `#`를 붙이지 않는다.** `feat/#12-...`가 아니라 `feat/12-...`다
- 반드시 이슈를 먼저 생성해 이슈번호를 확보한 뒤 브랜치를 만든다
- **`dev`에서 딴다.** `main`에서 따면 dev에 이미 들어간 남의 작업 위에서 작업하지 못해 나중에 충돌이 몰린다

```bash
git switch dev && git pull
git switch -c feat/12-interview-session
```

### PR 생성

- 제목 형식: `[{타입}] {요약}` — 예: `[feat] 면접 세션 생성 API 구현`
- **base 브랜치는 `dev`다.** GitHub이 기본값을 `main`으로 잡으면 반드시 바꾼다
- 본문에 `Closes #{이슈번호}`를 넣는다 — 머지되면 이슈가 자동으로 닫힌다
- 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 형식을 따른다
- 리뷰어는 `review-assign` 워크플로우가 자동 지정하므로 직접 지정하지 않는다
- 파일 10개 / 400줄을 넘으면 쪼개는 걸 고려한다

### 리뷰 코멘트 작성

반드시 고쳐야 하는 것과 취향인 것을 구분해서 표기한다.

```
[필수] 이 트랜잭션 안에서 AI 폴링을 하면 커넥션을 90초 붙듭니다
[제안] 이 메서드명은 findActiveByUserId 가 더 명확할 것 같아요
[질문] 여기서 null이 올 수 있나요?
```

### 머지

- **Squash merge**를 쓴다 — 작업 중 커밋이 `dev` 히스토리를 어지럽히지 않게
- 리뷰 1명 이상 승인 후 머지한다. 승인 없이 머지하지 않는다
- 머지 후 작업 브랜치는 삭제한다

### 이슈-브랜치-PR 전체 흐름

```
이슈 생성 → dev 최신화 → 브랜치 생성(이슈번호 포함) → 작업·커밋
→ PR 생성(base=dev, Closes #번호) → 리뷰 1명 이상 승인 → Squash merge → 브랜치 삭제
```

## Step 3 — 자가 검증

- [ ] 커밋 메시지가 `{타입}({스코프}): {요약}` 형식인가
- [ ] 스코프가 실제 건드린 도메인·계층 이름인가
- [ ] 브랜치명이 `{타입}/{이슈번호}-{영문-요약}`이고 `#`가 없는가
- [ ] 브랜치를 `dev`에서 땄는가
- [ ] PR 제목이 `[{타입}] {요약}` 형식인가
- [ ] PR base가 `dev`인가
- [ ] PR 본문에 `Closes #{번호}`가 있는가
- [ ] AI 트레일러(`Co-Authored-By` 등)가 없는가
