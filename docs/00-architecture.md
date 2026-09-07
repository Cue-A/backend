# 00. 아키텍처

## 서비스 개요

Cue&A는 취준생이 혼자 실전처럼 면접을 연습하고, 왜 못했는지와 어떻게 고칠지를
알려주는 AI 코칭 서비스입니다.

주요 기능:

- 이력서 기반 맞춤 질문 생성, 실시간 꼬리질문
- 종합 분석 리포트 (내용 · 음성 · 시선 3축)
- 재연습을 통한 회차별 성장 추적

---

## 구성

```
┌─────────────┐                    ┌─────────────────┐
│  React SPA  │ ──── REST/WS ────▶ │  Spring Boot    │
│  (프론트)    │ ◀─── push ──────── │  (이 저장소)     │
└─────────────┘                    └─────────────────┘
       │                                    │
       │ Presigned                          │ 폴링(1초)
       │ 업로드                              ▼
       │                           ┌─────────────────┐
       │                           │  Python AI 서버  │
       │                           │  FastAPI+Celery │
       │                           └─────────────────┘
       │                                    │
       ▼                                    │ mp3 업로드
┌─────────────────────────────────────────────┐
│              S3 / MinIO                      │
└─────────────────────────────────────────────┘
       ▲
       └── 프론트가 질문 오디오를 직접 재생

┌──────────────┐   ┌──────────────┐
│  PostgreSQL  │   │    Redis     │
│  영구 데이터   │   │  캐시·세션    │
└──────────────┘   └──────────────┘
```

이것은 **모놀리식**입니다. Spring과 Python이 나뉜 것은 언어 런타임 제약
(Whisper·LLM 라이브러리가 Python에만 있음)에 따른 프로세스 분리일 뿐,
도메인을 쪼갠 MSA가 아닙니다. Spring은 단일 애플리케이션, 단일 DB입니다.

---

## 책임 분리

| 항목 | Spring | Python AI |
|---|---|---|
| 인증/인가 | ✅ | ❌ |
| 세션 CRUD, 상태 전이 | ✅ | ❌ |
| 프론트 WebSocket | ✅ | ❌ |
| Presigned URL 발급 | ✅ | ❌ |
| 질문·답변 로그 영속 저장 | ✅ | ❌ |
| 회차 비교 계산 | ✅ | ❌ |
| 질문 생성, 난이도 배분, 토픽 진행 | ❌ | ✅ |
| STT / LLM / TTS | ❌ | ✅ |
| 시선·음성 분석, 점수 산출 | ❌ | ✅ |
| 질문 오디오 S3 업로드 | ❌ | ✅ |

---

## 통신 흐름

### 왜 Spring이 폴링하는가

AI 작업은 5~30초가 걸립니다. 프론트가 결과를 알아야 하는데 방식이 두 가지입니다.

**프론트도 폴링하면** 프론트→Spring 대기와 Spring→AI 대기가 누적되어 최대 2초가
밀리고, 세션당 100회 이상의 요청이 발생합니다. 더 큰 문제는 결과가 프론트로
직행하면 **브라우저를 닫는 순간 그 질문이 어디에도 저장되지 않는다**는 점입니다.
질문·답변 로그는 리포트와 재연습의 유일한 근거이므로 반드시 Spring을 거쳐야 합니다.

**Spring만 폴링하고 WebSocket으로 밀어주면** 대기가 한 번만 발생하고,
프론트는 요청을 보내지 않으며, 로그 저장이 보장됩니다.

### 예외 — 대용량 파일은 직통

제어는 Spring을 경유하지만 파일 바이트는 직통입니다.

- 이력서·녹음 업로드: 프론트 → S3 (Spring은 Presigned URL만 발급)
- 질문 오디오 재생: S3 → 프론트 (Spring은 URL만 전달)
- 질문 오디오 생성: AI → S3 (직접 업로드)

Spring을 통과시키면 서버 메모리와 대역폭이 파일 전송에 소모됩니다.

---

## 코드 구조

```
cue-a/
├── README.md
├── CLAUDE.md
├── docs/
├── .github/
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── ISSUE_TEMPLATE/
├── docker-compose.dev.yml          PG + Redis + MinIO (개발 중 상시)
├── docker-compose.yml              위 + Spring + AI + 프론트 (데모용)
├── .env.example
├── .dockerignore
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat
└── src/
    ├── main/
    │   ├── java/com/cuea/
    │   │   ├── CueAApplication.java
    │   │   │
    │   │   ├── common/                     ── 공통 기반
    │   │   │   ├── result/
    │   │   │   │   └── Result.java                 통일 응답
    │   │   │   ├── exception/
    │   │   │   │   ├── BusinessException.java
    │   │   │   │   ├── ErrorCode.java
    │   │   │   │   └── GlobalExceptionHandler.java
    │   │   │   ├── config/
    │   │   │   │   ├── CorsConfig.java
    │   │   │   │   ├── S3Config.java
    │   │   │   │   ├── WebSocketConfig.java
    │   │   │   │   ├── RedisConfig.java
    │   │   │   │   ├── JacksonConfig.java
    │   │   │   │   └── OpenApiConfig.java
    │   │   │   ├── annotation/
    │   │   │   │   └── RateLimit.java
    │   │   │   ├── aspect/
    │   │   │   │   └── RateLimitAspect.java
    │   │   │   └── security/
    │   │   │       ├── JwtProvider.java
    │   │   │       ├── JwtAuthFilter.java
    │   │   │       └── AiSecretFilter.java
    │   │   │
    │   │   ├── infrastructure/             ── 기술 계층
    │   │   │   ├── ai/                         ★ 핵심
    │   │   │   │   ├── AiClient.java               인터페이스
    │   │   │   │   ├── RealAiClient.java
    │   │   │   │   ├── MockAiClient.java           더미 서버 대체
    │   │   │   │   ├── AiPoller.java               폴링 + 타임아웃
    │   │   │   │   ├── AiErrorTranslator.java      에러 분류·재시도 판단
    │   │   │   │   └── dto/                        snake_case DTO
    │   │   │   ├── websocket/
    │   │   │   │   ├── SessionSocketHandler.java
    │   │   │   │   ├── SocketSessionRegistry.java
    │   │   │   │   └── message/                    프론트 push 메시지
    │   │   │   ├── file/
    │   │   │   │   ├── S3StorageService.java
    │   │   │   │   ├── PresignedUrlIssuer.java
    │   │   │   │   └── FileValidator.java
    │   │   │   ├── redis/
    │   │   │   │   ├── RedisService.java
    │   │   │   │   └── CompanyCache.java
    │   │   │   └── mapper/                         MapStruct
    │   │   │
    │   │   └── domain/                     ── 비즈니스 도메인
    │   │       ├── auth/
    │   │       ├── user/
    │   │       ├── document/                   이력서·포트폴리오
    │   │       ├── interview/                  세션, 질문·답변, 재연습
    │   │       │   ├── controller/
    │   │       │   ├── service/
    │   │       │   │   ├── InterviewSessionService.java
    │   │       │   │   ├── QuestionLogService.java
    │   │       │   │   └── ReplayService.java
    │   │       │   ├── repository/
    │   │       │   ├── entity/
    │   │       │   │   ├── InterviewSession.java
    │   │       │   │   └── QuestionLog.java
    │   │       │   └── dto/
    │   │       │       ├── request/
    │   │       │       └── response/
    │   │       ├── report/
    │   │       ├── growth/                     회차 비교
    │   │       └── company/                    AI 프록시
    │   │
    │   └── resources/
    │       ├── application.yml
    │       ├── application-local.yml           AI = localhost:8000
    │       ├── application-docker.yml          AI = python-ai:8000
    │       └── scripts/                        Redis Lua (RateLimit)
    │
    └── test/java/com/cuea/
```

각 도메인 모듈은 `controller / service / repository / entity / dto` 로 나눕니다.
위 트리에서는 `interview` 만 펼쳐 두었고 나머지도 같은 형태입니다.

### 계층 규칙

```
controller  →  service  →  repository
                  ↓
            infrastructure
```

- **컨트롤러는 서비스만 호출합니다.** 리포지토리를 직접 쓰지 않습니다
- **도메인 서비스는 `infrastructure` 를 통해서만 외부와 통신합니다.**
  `RestClient`, S3 SDK, Redis 템플릿을 도메인에서 직접 쓰지 않습니다
- **도메인 간 참조는 service 레벨에서만 합니다.**
  다른 도메인의 `repository` 나 `entity` 를 가져다 쓰지 않습니다
- `common` 은 모든 계층이 참조할 수 있지만, `common` 이 다른 곳을 참조하지 않습니다

### 왜 `infrastructure/ai/` 를 따로 두는가

AI 서버와의 통신은 폴링, 타임아웃 분기(90초/60초), 에러 분류, 재시도, snake_case
변환, 목 전환까지 관심사가 많습니다. 이걸 도메인 서비스에 흩어 놓으면 세션·리포트·
재연습이 각자 다른 방식으로 AI를 부르게 됩니다.

리포트 계약이 도착하면 같은 폴링 로직을 재사용해야 하므로,
**작업 종류에 무관하게 동작하도록 제네릭하게 설계합니다.**

---

## 기술 선택

| 항목 | 선택 | 이유 |
|---|---|---|
| Spring Boot 3.5 | 4.x 대신 | 자료와 라이브러리 호환성 |
| Java 21 가상 스레드 | 필수 | 폴링·WebSocket이 스레드를 오래 점유 |
| PostgreSQL 16 | | |
| Redis 7 | | 세션 캐시, 회사 목록 캐시 |
| MinIO | S3 호환 | 로컬 개발용. 배포 시 S3로 교체 가능 |

가상 스레드는 이 프로젝트에서 특히 중요합니다. Spring이 AI를 최대 90초 폴링하는
동안 스레드를 붙들고, WebSocket 연결도 스레드를 점유합니다. 플랫폼 스레드로 두면
톰캣 기본 200개가 금방 소진됩니다.

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

---

## 배포 구성

| 파일 | 용도 |
|---|---|
| `docker-compose.dev.yml` | 인프라만 (PG, Redis, MinIO). **개발 중 상시 사용** |
| `docker-compose.yml` | 전체 (인프라 + Spring + AI + 프론트). 데모용 |

개발 중에는 인프라만 도커로 띄우고 Spring은 IntelliJ에서 실행합니다.
디버거와 핫리로드를 쓸 수 있습니다.

### AI 서버 주소

컨테이너마다 `localhost`가 다르므로 프로파일로 분리합니다.

| 프로파일 | 주소 |
|---|---|
| `local` (IDE 실행) | `http://localhost:8000` |
| `docker` (compose 실행) | `http://python-ai:8000` |
