# 01. 컨벤션

## 패키지

```
com/cuea/domain/{도메인}/
├── controller/     REST 컨트롤러
├── service/        비즈니스 로직
├── repository/     JPA 리포지토리
├── entity/         엔티티
└── dto/
    ├── request/
    └── response/
```

도메인 간 참조는 `service` 레벨에서만 합니다.
다른 도메인의 `repository`나 `entity`를 직접 가져다 쓰지 않습니다.

---

## API 응답

모든 응답은 `Result<T>` 로 감쌉니다.

```java
public record Result<T>(
        boolean success,
        T data,
        String errorCode,
        String message
) {
    public static <T> Result<T> ok(T data) { ... }
    public static <T> Result<T> fail(String code, String message) { ... }
}
```

```json
// 성공
{ "success": true, "data": { ... }, "errorCode": null, "message": null }

// 실패
{ "success": false, "data": null, "errorCode": "SESSION_NOT_FOUND", "message": "세션을 찾을 수 없습니다" }
```

---

## 예외 처리

컨트롤러에서 `try-catch` 하지 않습니다. `BusinessException` 을 던지고
`GlobalExceptionHandler` 가 처리합니다.

```java
throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
```

`ErrorCode` 는 enum으로 코드·HTTP 상태·기본 메시지를 함께 정의합니다.

---

## 네이밍

| 대상 | 규칙 | 예 |
|---|---|---|
| Java 필드·메서드 | camelCase | `sessionId`, `findByUserId` |
| JSON (프론트 방향) | camelCase | `{"sessionId": "..."}` |
| JSON (AI 서버 방향) | **snake_case** | `{"session_id": "..."}` |
| DB 컬럼 | snake_case | `session_id` |
| 상수 | UPPER_SNAKE | `MAX_RETRY_COUNT` |

AI 서버는 Python이라 snake_case를 씁니다. `infrastructure/ai/` 의 DTO에만
`@JsonNaming(SnakeCaseStrategy.class)` 를 붙이고, 나머지는 camelCase입니다.
경계를 넘을 때 변환하고, 도메인 안으로 snake_case를 들이지 않습니다.

---

## DTO

- 요청·응답 DTO는 `record` 로 만듭니다.
- 엔티티를 컨트롤러에서 직접 반환하지 않습니다.
- 엔티티 ↔ DTO 변환은 MapStruct를 씁니다. 단순한 경우 정적 팩토리 메서드도 허용합니다.

```java
public record SessionCreateRequest(
        @NotNull String resumeId,
        @NotBlank String jobRole,
        @NotNull Persona persona,
        String companyId,
        Integer questionCount
) {}
```

---

## 엔티티

- `@Setter` 를 클래스 단위로 붙이지 않습니다. 상태 변경은 의미 있는 메서드로 노출합니다.

```java
// 나쁨
session.setStatus(Status.COMPLETED);

// 좋음
session.complete();
```

- 연관관계는 기본 `LAZY`.
- `@Builder` 사용, 기본 생성자는 `@NoArgsConstructor(access = PROTECTED)`.

---

## 트랜잭션

- `@Transactional` 은 서비스 레이어에 붙입니다.
- 조회 전용은 `@Transactional(readOnly = true)`.
- **외부 호출(AI 서버, S3)을 트랜잭션 안에 넣지 않습니다.** 커넥션을 오래 붙들게 됩니다.
  AI 폴링은 최대 90초이므로 특히 주의합니다.

```java
// 나쁨 — 90초 동안 DB 커넥션 점유
@Transactional
public void startSession(...) {
    var session = repository.save(...);
    var result = aiClient.startAndPoll(...);   // 최대 90초
    session.updateFirstQuestion(result);
}

// 좋음 — 트랜잭션을 쪼갠다
public void startSession(...) {
    var sessionId = createSession(...);        // 짧은 트랜잭션
    var result = aiClient.startAndPoll(...);   // 트랜잭션 밖
    saveFirstQuestion(sessionId, result);      // 짧은 트랜잭션
}
```

---

## 로깅

- `@Slf4j` 사용. `System.out.println` 금지.
- AI 서버 호출은 `sessionId`, `taskId`, 소요시간을 남깁니다.
- 개인정보(답변 전문, 이력서 내용)를 로그에 남기지 않습니다.

```java
log.info("AI 작업 완료 sessionId={} taskId={} elapsedMs={}", sessionId, taskId, elapsed);
```

---

## 테스트

- 서비스 레이어는 단위 테스트, 컨트롤러는 `@WebMvcTest`.
- AI 클라이언트는 목으로 대체합니다. 실제 AI 서버에 붙는 테스트는 만들지 않습니다.
- 테스트 메서드명은 한글로 써도 됩니다.

```java
@Test
void 재연습_세션은_항상_최초_세션을_참조한다() { ... }
```

---

## 작업 흐름 — 이슈 → 브랜치 → PR

모든 작업은 이슈에서 시작합니다. 이슈 없이 브랜치를 따지 않습니다.

```
1. 이슈 생성            GitHub → New issue → 기능 개발 / 버그 선택
2. dev 최신화           git switch dev && git pull
3. 브랜치 생성          git switch -c feat/12-interview-session   (12 = 이슈 번호)
4. 작업 + 커밋
5. PR 생성              base 를 dev 로  ← 기본값이 main 이면 바꿔주세요
6. 본문에 Closes #12    머지 시 이슈 자동 종료
7. 리뷰 1명 이상 승인
8. Squash merge → 브랜치 삭제
```

### 브랜치 구조

```
main   배포 가능한 상태만. dev 에서만 넘어옵니다
 ↑
dev    통합 브랜치. 팀원 작업이 모이는 곳
 ↑
feat/12-interview-session   각자 파서 쓰는 작업 브랜치
```

**작업 브랜치는 dev 에서 따고 dev 로 되돌립니다.** main 으로 PR 을 올리지 마세요.

main 을 따로 두는 이유는, 데모나 발표 중에 dev 가 깨져 있어도 보여줄 수 있는
상태를 하나 남겨두기 위해서입니다. dev → main 은 배포 시점에만 합칩니다.

### 왜 이슈부터 만드나

- 누가 무엇을 하는지 팀원이 볼 수 있습니다. 같은 걸 두 명이 만드는 사고를 막습니다
- 작업 범위를 미리 쪼개면 PR이 작아집니다. 큰 PR은 아무도 제대로 리뷰하지 못합니다
- **`docs/90-open-questions.md` 에 걸리는 게 있는지 이슈 단계에서 걸러집니다.**
  미확정 사항을 모르고 구현했다가 갈아엎는 일이 줄어듭니다

### 브랜치 이름

```
{타입}/{이슈번호}-{영문-요약}

feat/12-interview-session
fix/31-replay-root-session
docs/45-report-contract
```

이슈 번호를 넣으면 브랜치만 보고도 맥락을 찾아갈 수 있습니다.
**dev 에서 따세요.** main 에서 따면 dev 에 이미 들어간 남의 작업 위에서
작업하지 못해 나중에 충돌이 몰립니다.

### 커밋 메시지

```
{타입}({스코프}): {요약}

feat:     기능 추가
fix:      버그 수정
refactor: 리팩터링
docs:     문서
test:     테스트
chore:    빌드·설정
```

예: `feat(interview): 면접 세션 생성 API 추가`

**스코프는 어디를 건드렸는지입니다.** 도메인 이름이나 계층 이름을 씁니다.

```
도메인   auth  user  document  interview  report  growth  company
계층     common  ai  websocket  storage  redis  security  config
기타     build  docker  github  domain  guide
```

```
feat(interview): 재연습 세션 생성 API 추가
fix(ai): 세션 시작 타임아웃을 90초로 분리
refactor(replay): 루트 세션 추적을 ReplayService 로 이동
docs(guide): 브랜치 전략을 dev 통합으로 변경
test(interview): 되묻기가 문항 수에 포함되지 않는지 검증
```

여러 곳을 건드려 스코프를 하나로 못 고르겠으면 **PR 을 쪼개라는 신호**입니다.
저장소 전체에 걸치는 변경(빌드 설정 등)만 스코프를 생략할 수 있습니다.

한 줄로 부족하면 본문에 이유를 적습니다. **무엇을 했는지보다 왜 했는지**를 씁니다.
무엇을 했는지는 diff를 보면 됩니다.

### PR 규칙

- **이슈를 먼저 만들고 그 이슈에서 시작합니다. 이슈 없는 PR 은 올리지 않습니다**
- 본문에 `Closes #12` 를 반드시 넣습니다. 머지되면 이슈가 자동으로 닫힙니다
- 제목: `[feat] 면접 세션 생성 API 구현`
- **base 브랜치는 `dev`** 입니다. GitHub 이 기본값을 main 으로 잡아주면 바꿔주세요
- 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 가 자동으로 채웁니다
- **`main` · `dev` 직접 푸시 금지.** 브랜치 보호 규칙으로 막아둡니다
- 리뷰 1명 이상 승인 후 머지
- **Squash merge** 를 씁니다. 작업 중 커밋이 `dev` 히스토리를 어지럽히지 않게

### 이슈를 먼저 파는 이유

PR 을 열고 나서야 "이거 왜 하는 거예요?" 를 묻게 되면 이미 코드가 다 쓰인
뒤입니다. 방향이 틀렸어도 되돌리기 아까워서 그대로 머지되기 쉽습니다.

이슈 단계에서 정하고 가면 이런 게 걸러집니다.

- 같은 걸 두 명이 만들고 있는지
- `docs/90-open-questions.md` 의 미확정 사항에 걸리는지
- 작업이 너무 커서 쪼개야 하는지

**이미 코드를 쓰기 시작했더라도 PR 전에 이슈를 만드세요.** 순서가 뒤집혔을 뿐
이슈가 하는 역할은 같습니다. 이슈 번호가 있어야 브랜치 이름
(`feat/12-...`)과 `Closes #12` 를 채울 수 있습니다.

### PR 크기

파일 10개 / 400줄을 넘으면 쪼개는 걸 고려하세요. 리뷰어가 대충 승인하게 됩니다.
이슈를 쪼개면 PR도 자연히 작아집니다.

---

## 리뷰

- 승인 없이 머지하지 않습니다. 급하면 오프라인으로 봐달라고 하세요
- 지적은 코드에 하고 사람에게 하지 않습니다
- 반드시 고쳐야 하는 것과 취향인 것을 구분해서 말합니다

```
[필수] 이 트랜잭션 안에서 AI 폴링을 하면 커넥션을 90초 붙듭니다
[제안] 이 메서드명은 findActiveByUserId 가 더 명확할 것 같아요
[질문] 여기서 null이 올 수 있나요?
```

---

## GitHub 저장소 설정

한 번만 해두면 됩니다.

**기본 브랜치를 `dev` 로** 바꿉니다. (Settings → General → Default branch)
PR 을 열 때 base 가 자동으로 dev 가 되어 실수로 main 에 올리는 일이 줄어듭니다.

**보호 규칙은 `dev` 와 `main` 둘 다** 겁니다.
(Settings → Branches → Add rule)

- ☑ Require a pull request before merging
- ☑ Require approvals — 1
- ☑ Automatically delete head branches (Settings → General)
- Allow squash merging만 켜고 나머지 두 개는 끄기

main 에 규칙을 안 걸면 dev 를 지켜봐야 소용이 없습니다. 급할 때 main 으로
바로 밀어버리게 됩니다.
