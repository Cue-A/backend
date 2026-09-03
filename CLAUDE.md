# CLAUDE.md

Claude Code가 이 저장소에서 작업할 때 참고하는 문서입니다.

---

## 프로젝트

**Cue&A** — AI 면접 코칭 서비스의 백엔드.
취준생이 혼자 실전처럼 면접을 연습하고, 왜 못했는지와 어떻게 고칠지를 받는 서비스.

이 저장소는 **Spring Boot 백엔드만** 담당합니다.
STT, LLM, TTS, 시선 분석은 전부 별도 Python AI 서버에서 처리하며,
이 저장소에는 AI 모델이나 프롬프트 코드가 없습니다.

### 이름 규칙

서비스명은 **Cue&A** 지만 `&` 는 Java 식별자에 쓸 수 없습니다. 코드에서는 아래를 씁니다.

| 위치 | 값 |
|---|---|
| 서비스명 (문서·UI) | Cue&A |
| Spring Initializr `Name` | `cue-a` |
| Spring Initializr `Artifact` | `cue-a` |
| Spring Initializr `Group` | `com.cuea` |
| Java 패키지 | `com.cuea` |
| 메인 클래스 | `CueAApplication` |
| GitHub 저장소 / 루트 디렉토리 | `cue-a` |
| DB 이름 / 유저 | `cuea` |
| S3 버킷 | `cue-a-media` |

`cue&a`, `cue_a`, `cueanda`, `cue-and-a` 를 패키지나 클래스명에 쓰지 마세요.

**S3 버킷명에는 언더스코어를 쓸 수 없습니다.** 소문자·숫자·하이픈·점만 허용됩니다.
자바 패키지에는 하이픈을 쓸 수 없어 `com.cuea` 로 붙여 씁니다.

### 대원칙

1. **Spring이 서비스 상태의 소유자다.** 사용자에게 보이는 상태는 항상 Spring DB 기준
2. **Python은 프론트엔드를 모른다.** 사용자에게 가는 모든 것은 Spring을 거친다
3. **AI 로직을 Spring에 구현하지 않는다.** 질문 생성, 평가, 난이도 배분은 AI 서버 영역

---

## 문서 인덱스

작업 전에 관련 문서를 읽으세요. 전부 읽을 필요는 없습니다.

| 작업 내용 | 읽을 문서 |
|---|---|
| 전체 구조 파악 | `docs/00-architecture.md` |
| 코드 작성 (항상) | `docs/01-conventions.md` |
| 엔티티·리포지토리 | `docs/02-database.md` |
| AI 서버 호출, 폴링, 에러 처리 | `docs/10-ai-client.md` |
| 면접 세션, 질문·답변 | `docs/11-interview.md` |
| 재연습 | `docs/12-replay.md` |
| 리포트, 성장 추적 | `docs/13-report.md` |
| 파일 업로드, S3 | `docs/20-storage.md` |
| 결정되지 않은 것 확인 | `docs/90-open-questions.md` |

---

## 명령어

```bash
# 인프라 기동 (PostgreSQL, Redis, MinIO)
docker compose -f docker-compose.dev.yml up -d

# 애플리케이션 실행
./gradlew bootRun --args='--spring.profiles.active=local'

# 테스트
./gradlew test

# 특정 테스트만
./gradlew test --tests '*InterviewServiceTest*'

# 빌드
./gradlew build
```

---

## 패키지 구조

```
com/cuea/
├── common/              공통 기반
│   ├── result/          Result<T> 통일 응답
│   ├── exception/       비즈니스 예외 + 전역 핸들러
│   ├── config/          CORS, S3, WebSocket, Jackson, OpenAPI
│   ├── annotation/      @RateLimit
│   ├── aspect/          RateLimitAspect
│   └── security/        JWT, AI 서버 시크릿
├── infrastructure/      기술 계층
│   ├── ai/              AI 서버 클라이언트 (요청·폴링·타임아웃·에러분류)
│   ├── websocket/       프론트 push
│   ├── file/            S3 Presigned URL
│   ├── redis/           세션 캐시, 회사 목록 캐시
│   └── mapper/          MapStruct
└── domain/               비즈니스 도메인
    ├── auth/            인증
    ├── user/            사용자
    ├── document/        이력서·포트폴리오
    ├── interview/       면접 세션, 질문·답변 로그, 재연습
    ├── report/          리포트
    ├── growth/          회차 비교
    └── company/         회사 목록 (AI 프록시)
```

각 도메인 모듈은 `controller` / `service` / `repository` / `entity` / `dto` 로 나눕니다.

---

## 작업할 때 지켜야 할 것

### 하지 말 것

- **`docs/90-open-questions.md` 에 있는 미확정 사항을 임의로 결정하지 마세요.**
  해당 영역 작업이 필요하면 어떤 결정이 필요한지 먼저 알려주세요.
- **AI 서버의 응답 필드명을 Java enum으로 변환하지 마세요.**
  특히 `category`(한글 8종)는 문자열 그대로 저장하고 그대로 되돌려줍니다.
  이유는 `docs/02-database.md` 참고.
- **`ddl-auto: create` 를 쓰지 마세요.** 재시작마다 데이터가 사라집니다.
- **AI 서버 주소를 코드에 하드코딩하지 마세요.** 프로파일 설정값을 씁니다.
- **엔티티를 컨트롤러에서 직접 반환하지 마세요.** 항상 DTO로 변환합니다.

### 항상 할 것

- 새 API는 `Result<T>` 로 감싸 반환합니다.
- 비즈니스 예외는 `BusinessException` 을 던지고 전역 핸들러가 처리합니다.
  컨트롤러에서 `try-catch` 하지 않습니다.
- AI 서버 호출은 반드시 `infrastructure/ai/` 의 클라이언트를 통합니다.
  도메인 서비스에서 `RestClient` 를 직접 쓰지 않습니다.
- 시간이 걸리는 작업(AI 폴링 등)은 가상 스레드 위에서 돕니다.
  `spring.threads.virtual.enabled=true` 가 이미 켜져 있습니다.

---

## 자주 헷갈리는 지점

**세션 상태가 두 군데 있습니다.**
AI 서버도 자체적으로 세션 상태(진행 중인 토픽, 꼬리질문 횟수 등)를 들고 있습니다.
Spring DB의 `status` 와 다른 개념이며, 사용자에게 보이는 것은 **Spring DB 기준**입니다.

**`is_replay` 와 `is_spare_topic` 은 축이 다릅니다.**
회차 비교 필터에는 `is_replay` 만 씁니다. 자세한 것은 `docs/12-replay.md`.

**`retry_of_session_id` 는 직전 회차가 아니라 항상 최초 세션입니다.**
3회차 이상에서 실수하기 쉽습니다. `docs/12-replay.md` 의 루트 세션 추적 참고.

**되묻기(`reask`)는 문항 수에 포함되지 않습니다.**
`question_number` 가 올라가지 않으므로 진행률 계산에 주의하세요.
