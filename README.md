# Cue&A Backend

AI 면접 코칭 서비스 Cue&A의 백엔드 서버입니다.

- **스택** Spring Boot 3.5 · Java 21 · PostgreSQL 16 · Redis 7 · MinIO(S3)
- **역할** 서비스 상태 관리, AI 서버 연동, 프론트 WebSocket 통신
- **AI 연산** 별도 Python 서버에서 처리합니다. 이 저장소에는 AI 코드가 없습니다.

> 서비스명은 **Cue&A** 지만 `&` 는 Java 식별자에 쓸 수 없습니다.
> 코드에서는 패키지 `com.cuea`, 클래스 `CueAApplication`, 저장소 `cue-a`,
> S3 버킷 `cue-a-media` 를 씁니다.

문서는 [`docs/`](./docs) 를 보세요. 인덱스는 [`CLAUDE.md`](./CLAUDE.md) 에 있습니다.

---

## 1. 사전 준비 (macOS)

### 1.1 Homebrew

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Apple Silicon이면 설치 후 안내대로 PATH를 잡아야 합니다.

```bash
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
source ~/.zprofile
```

### 1.2 JDK 21

IntelliJ에서 받는 게 제일 편합니다. 프로젝트를 열면 SDK 선택 창이 뜨는데,
`Download JDK` → **Temurin 21** 을 고르면 끝입니다.

터미널에서도 쓰고 싶으면:

```bash
brew install openjdk@21
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
  /Library/Java/JavaVirtualMachines/openjdk-21.jdk
java -version   # openjdk 21.x 확인
```

### 1.3 Docker Desktop

```bash
brew install --cask docker
```

설치 후 **앱을 한 번 실행**해야 데몬이 뜹니다. 상단 메뉴바에 고래 아이콘이
보이면 준비된 겁니다.

### 1.4 IntelliJ IDEA

Community Edition으로 충분합니다.

```bash
brew install --cask intellij-idea-ce
```

---

## 2. 프로젝트 실행

### 2.1 클론

```bash
git clone <저장소 주소>
cd cue-a
```

### 2.2 환경변수

```bash
cp .env.example .env
```

로컬 개발은 기본값 그대로 돌아갑니다. `.env`는 **절대 커밋하지 마세요.**
`.gitignore`에 이미 들어 있습니다.

### 2.3 인프라 기동

PostgreSQL, Redis, MinIO를 도커로 띄웁니다.

```bash
docker compose -f docker-compose.dev.yml up -d
```

확인:

```bash
docker compose -f docker-compose.dev.yml ps
```

세 개(+ 버킷 초기화 작업)가 모두 떠 있어야 합니다.

| 서비스 | 주소 | 계정 |
|---|---|---|
| PostgreSQL | `localhost:5432` | `cuea` / `cuea` |
| Redis | `localhost:6379` | — |
| MinIO 콘솔 | http://localhost:9001 | `minioadmin` / `minioadmin` |

버킷(`cue-a-media`)은 자동으로 만들어집니다. 콘솔에서 직접 만들 필요 없습니다.

### 2.4 애플리케이션 실행

IntelliJ에서 `CueAApplication` 우클릭 → Run.

터미널에서 하려면:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

http://localhost:8080/swagger-ui.html 이 열리면 성공입니다.

### 2.5 종료

```bash
# 컨테이너만 정지 (데이터 유지)
docker compose -f docker-compose.dev.yml down

# 데이터까지 삭제
docker compose -f docker-compose.dev.yml down -v
```

---

## 3. IntelliJ 설정

### 3.1 Annotation Processing

MapStruct와 Lombok을 쓰므로 반드시 켜야 합니다. 안 켜면
`Cannot find symbol: getXxx()` 같은 오류가 쏟아집니다.

```
Settings → Build, Execution, Deployment → Compiler → Annotation Processors
  ☑ Enable annotation processing
```

### 3.2 프로파일 지정

```
Run/Debug Configurations → Modify options → Add VM options
  -Dspring.profiles.active=local
```

또는 Environment variables에 `SPRING_PROFILES_ACTIVE=local`.

### 3.3 .env 읽기

Gradle의 `bootRun`은 루트 `.env`를 자동으로 읽도록 설정해두었습니다.
IntelliJ Run 버튼으로 실행할 때도 쓰려면 **EnvFile** 플러그인을 설치하고
Run Configuration에서 `.env`를 지정하세요.

### 3.4 인코딩

```
Settings → Editor → File Encodings
  Global / Project / Default: UTF-8
```

한글 카테고리명(`협업·갈등` 등)을 다루므로 어긋나면 깨집니다.

---

## 4. AI 서버 연동

AI 서버는 별도 저장소이고 Python으로 되어 있습니다.
백엔드 단독 개발 중에는 **더미 서버**를 씁니다.

```yaml
# application-local.yml
app:
  ai:
    base-url: http://localhost:8000
```

AI 서버가 아직 없으면 `app.ai.mock.enabled=true` 로 두면
고정 응답을 반환하는 내부 목이 동작합니다. (구현 예정)

> **주의** docker compose 안에서 Spring을 띄울 때는 주소가
> `http://python-ai:8000` 입니다. 컨테이너마다 `localhost`가 다릅니다.
> 프로파일(`local` / `docker`)로 분리되어 있으니 섞어 쓰지 마세요.

---

## 5. 자주 겪는 문제

**포트가 이미 사용 중이라고 나옵니다**

```bash
lsof -i :5432        # 점유 프로세스 확인
brew services stop postgresql   # 로컬에 PG를 깔았다면
```

**테이블이 안 생깁니다 / 데이터가 사라집니다**

`ddl-auto`가 `update`인지 확인하세요. `create`면 재시작마다 전부 지워집니다.

**Docker 이미지 pull이 안 됩니다 (Apple Silicon)**

대부분 arm64를 지원하지만, 안 되는 이미지가 있으면 `platform: linux/amd64`를
compose에 추가하세요. 느려지지만 동작은 합니다.

**MinIO에 파일을 올렸는데 못 읽습니다**

버킷 정책과 CORS 설정을 확인하세요. 자세한 내용은 [`docs/20-storage.md`](./docs/20-storage.md).

**한글이 깨집니다**

IntelliJ 인코딩(3.4)과 DB 콜레이션을 확인하세요.

---

## 6. 프로젝트 구조

```
cue-a/
├── README.md
├── CLAUDE.md                       Claude 작업 규칙 + 문서 인덱스
├── docs/                           설계 문서
├── .github/
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── ISSUE_TEMPLATE/
│
├── docker-compose.dev.yml          인프라만 (PG + Redis + MinIO) — 개발 중 상시 사용
├── docker-compose.yml              전체 (인프라 + Spring + AI + 프론트) — 데모용
├── .env.example                    환경변수 템플릿 (.env는 커밋 금지)
├── .dockerignore
│
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat
│
└── src/
    ├── main/
    │   ├── java/com/cuea/
    │   │   ├── CueAApplication.java
    │   │   │
    │   │   ├── common/                 공통 기반
    │   │   │   ├── result/                 Result<T> 통일 응답
    │   │   │   ├── exception/              비즈니스 예외 + 전역 핸들러
    │   │   │   ├── config/                 CORS, S3, WebSocket, Redis, Jackson
    │   │   │   ├── annotation/             @RateLimit
    │   │   │   ├── aspect/                 RateLimitAspect
    │   │   │   └── security/               JWT, AI 서버 시크릿
    │   │   │
    │   │   ├── infrastructure/         기술 계층
    │   │   │   ├── ai/                     ★ AI 클라이언트 (폴링·타임아웃·에러분류)
    │   │   │   ├── websocket/              프론트 push
    │   │   │   ├── file/                   S3 Presigned URL
    │   │   │   ├── redis/                  세션 캐시, 회사 목록 캐시
    │   │   │   └── mapper/                 MapStruct
    │   │   │
    │   │   └── domain/                 비즈니스 도메인
    │   │       ├── auth/
    │   │       ├── user/
    │   │       ├── document/               이력서·포트폴리오
    │   │       ├── interview/              면접 세션, 질문·답변 로그, 재연습
    │   │       ├── report/                 리포트
    │   │       ├── growth/                 회차 비교
    │   │       └── company/                회사 목록 (AI 프록시)
    │   │
    │   └── resources/
    │       ├── application.yml
    │       ├── application-local.yml       AI = localhost:8000
    │       ├── application-docker.yml      AI = python-ai:8000
    │       └── scripts/                    Redis Lua (RateLimit)
    │
    └── test/java/com/cuea/
```

각 도메인 모듈은 `controller / service / repository / entity / dto` 로 나눕니다.

### Docker 파일 두 개를 나눈 이유

| 파일 | 띄우는 것 | 언제 |
|---|---|---|
| `docker-compose.dev.yml` | PostgreSQL, Redis, MinIO | **개발 중 상시.** Spring은 IntelliJ에서 실행 |
| `docker-compose.yml` | 위 + Spring + AI + 프론트 | 데모·발표 직전 통합 확인 |

개발 중에 Spring까지 컨테이너로 띄우면 디버거를 못 붙이고 코드 고칠 때마다
이미지를 다시 빌드해야 합니다. 인프라만 도커로 띄우고 앱은 IDE에서 돌립니다.

### 계층 규칙

```
controller  →  service  →  repository
                  ↓
            infrastructure
```

- 컨트롤러는 서비스만 호출합니다. 리포지토리를 직접 쓰지 않습니다
- 도메인 서비스는 `infrastructure` 를 통해서만 외부와 통신합니다.
  `RestClient`, S3 SDK를 도메인에서 직접 쓰지 않습니다
- 도메인 간 참조는 service 레벨에서만 합니다

파일 단위 상세 트리는 [`docs/00-architecture.md`](./docs/00-architecture.md) 에 있습니다.

---

## 7. 개발 흐름

모든 작업은 이슈에서 시작합니다.

```
1. GitHub → New issue → 기능 개발 / 버그 선택
2. 브랜치 생성       feat/12-interview-session   (12 = 이슈 번호)
3. 작업 + 커밋       feat: 면접 세션 생성 API 추가
4. PR 생성           템플릿이 자동으로 채워짐
5. 본문에 Closes #12
6. 리뷰 1명 이상 승인 → Squash merge
```

`main` 직접 푸시는 막혀 있습니다.
상세 규칙은 [`docs/01-conventions.md`](./docs/01-conventions.md) 의 작업 흐름 항목.

---

## 8. 문서

| 문서 | 내용 |
|---|---|
| [`CLAUDE.md`](./CLAUDE.md) | Claude 작업 규칙 + 문서 인덱스 |
| [`docs/00-architecture.md`](./docs/00-architecture.md) | 서비스 개요, 통신 구조 |
| [`docs/01-conventions.md`](./docs/01-conventions.md) | 코드·API·커밋 컨벤션 |
| [`docs/02-database.md`](./docs/02-database.md) | DB 스키마 |
| [`docs/10-ai-client.md`](./docs/10-ai-client.md) | AI 서버 연동 |
| [`docs/11-interview.md`](./docs/11-interview.md) | 면접 세션, 질문 유형 |
| [`docs/12-replay.md`](./docs/12-replay.md) | 재연습 |
| [`docs/13-report.md`](./docs/13-report.md) | 리포트 |
| [`docs/20-storage.md`](./docs/20-storage.md) | S3 / 파일 |
| [`docs/90-open-questions.md`](./docs/90-open-questions.md) | 미확정 사항 |
