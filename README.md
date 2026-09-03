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

## 빠른 시작

JDK 21 과 도커만 있으면 됩니다. 처음이면 [1. 사전 준비](#1-사전-준비) 부터 보세요.

```bash
git clone git@github.com:Cue-A/backend.git
cd backend

cp .env.example .env                              # 로컬은 기본값 그대로 동작
docker compose -f docker-compose.dev.yml up -d    # PG · Redis · MinIO 기동
./gradlew bootRun --args='--spring.profiles.active=local'
```

http://localhost:8080/swagger-ui.html 이 열리면 성공입니다.
**AI 서버가 없어도 돌아갑니다.** `app.ai.mock.enabled=true` 가 기본이라
내부 목이 고정 응답을 돌려줍니다.

작업을 마쳤으면:

```bash
docker compose -f docker-compose.dev.yml down     # 컨테이너만 정지 (데이터 유지)
```

| 자주 쓰는 명령 | |
|---|---|
| `docker compose -f docker-compose.dev.yml up -d` | 인프라 기동 |
| `docker compose -f docker-compose.dev.yml ps` | 인프라 상태 확인 |
| `docker compose -f docker-compose.dev.yml down` | 인프라 정지 (데이터 유지) |
| `docker compose -f docker-compose.dev.yml down -v` | 인프라 정지 + **데이터 삭제** |
| `./gradlew bootRun --args='--spring.profiles.active=local'` | 앱 실행 |
| `./gradlew test` | 테스트 |
| `./gradlew build` | 빌드 |

---

## 1. 사전 준비

필요한 건 **JDK 21** 과 **도커** 두 개뿐입니다. 아래는 macOS 기준 설치 방법이며,
이미 깔려 있으면 건너뛰세요.

```bash
java -version    # openjdk 21.x 가 나오면 OK
docker info      # 오류 없이 정보가 나오면 OK
```

### 1.1 JDK 21

셋 중 아무거나 편한 걸 쓰면 됩니다.

- **IntelliJ 로 받기 (제일 쉬움)** — 프로젝트를 열면 SDK 선택 창이 뜹니다.
  `Download JDK` → **Temurin 21**
- **설치 파일** — [adoptium.net](https://adoptium.net) 에서 macOS `.pkg` 받아 실행
- **Homebrew** — 이미 쓰고 있다면

  ```bash
  brew install openjdk@21
  sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
    /Library/Java/JavaVirtualMachines/openjdk-21.jdk
  ```

Gradle 은 따로 설치하지 않습니다. `./gradlew` 가 알아서 받습니다.

> Java 21 이어야 합니다. 25 나 17 로는 빌드가 깨집니다.
> 여러 버전이 깔려 있으면 `/usr/libexec/java_home -V` 로 확인하세요.

### 1.2 도커

PostgreSQL · Redis · MinIO 를 컨테이너로 띄웁니다. 직접 설치하지 마세요.

컨테이너를 돌릴 수 있으면 뭘 쓰든 상관없습니다. **Docker Desktop 이 필수는
아닙니다.**

- **Docker Desktop** — 가장 무난합니다. [docker.com](https://www.docker.com/products/docker-desktop/)
  에서 받거나 `brew install --cask docker`.
  설치 후 **앱을 한 번 실행**해야 데몬이 뜹니다. 메뉴바에 고래 아이콘이 보이면 준비 완료
- **OrbStack** — 맥에서 더 가볍고 빠릅니다. `brew install --cask orbstack`
- **Colima** — 터미널만 쓰고 싶다면. `brew install colima docker && colima start`

셋 중 뭘 쓰든 `docker compose` 명령은 동일합니다.

### 1.3 Homebrew (선택)

**필수가 아닙니다.** 위 설치를 명령어 한 줄로 끝내고 싶을 때만 쓰세요.
설치 파일로 받아도 아무 문제 없습니다.

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Apple Silicon 이면 설치 후 안내대로 PATH 를 잡아야 합니다.

```bash
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
source ~/.zprofile
```

### 1.4 에디터

아무거나 쓰세요. IntelliJ IDEA Community 로 충분합니다.

**IntelliJ 를 쓴다면 두 가지만 켜두세요.** 안 하면 바로 막힙니다.

```
Settings → Build, Execution, Deployment → Compiler → Annotation Processors
  ☑ Enable annotation processing        ← Lombok · MapStruct 가 이걸 씁니다

Settings → Editor → File Encodings
  Global / Project / Default: UTF-8     ← 한글 카테고리명(협업·갈등)이 깨집니다
```

실행은 `CueAApplication` 우클릭 → Run. 프로파일은 Run/Debug Configurations 에서
환경변수 `SPRING_PROFILES_ACTIVE=local` 을 주거나, 그냥 터미널에서
`./gradlew bootRun --args='--spring.profiles.active=local'` 을 쓰면 됩니다.

`.env` 는 `bootRun` 이 자동으로 읽습니다. IntelliJ 의 Run 버튼으로 실행할 때도
읽게 하려면 **EnvFile** 플러그인을 설치하고 Run Configuration 에서 `.env` 를
지정하세요.

---

## 2. 프로젝트 실행

### 2.1 클론

```bash
git clone git@github.com:Cue-A/backend.git
cd backend
```

`dev` 브랜치가 통합 브랜치입니다. 작업 브랜치는 여기서 땁니다.

```bash
git switch dev
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

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

http://localhost:8080/swagger-ui.html 이 열리면 성공입니다.

IDE 를 쓴다면 `CueAApplication` 우클릭 → Run 으로도 됩니다.
이때는 프로파일(`SPRING_PROFILES_ACTIVE=local`)을 직접 지정해야 합니다.

**AI 서버는 없어도 됩니다.** `app.ai.mock.enabled` 가 기본 `true` 라
내부 목이 응답합니다. 실제 AI 서버에 붙일 때만 `.env` 에서 `false` 로 바꾸세요.

### 2.5 종료

```bash
# 컨테이너만 정지 (데이터 유지)
docker compose -f docker-compose.dev.yml down

# 데이터까지 삭제
docker compose -f docker-compose.dev.yml down -v
```

---

## 3. AI 서버 연동

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

## 4. 자주 겪는 문제

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

에디터 인코딩을 UTF-8 로 맞추세요. IntelliJ 면
`Settings → Editor → File Encodings` 의 세 항목을 전부 UTF-8 로 둡니다.
DB 는 compose 에서 UTF8 로 초기화하고 있습니다.

**`Cannot find symbol: getXxx()` / `DocumentMapperImpl` 을 못 찾습니다**

Lombok · MapStruct 가 만드는 코드입니다. IDE 의 annotation processing 이
꺼져 있으면 이 오류가 쏟아집니다.
`Settings → Build, Execution, Deployment → Compiler → Annotation Processors`
에서 **Enable annotation processing** 을 켜세요.
터미널의 `./gradlew build` 는 이 설정과 무관하게 항상 동작합니다.

**Java 버전 오류가 납니다**

Java 21 이어야 합니다. `/usr/libexec/java_home -V` 로 깔린 버전을 보고,
21 이 없으면 [1.1](#11-jdk-21) 을 보세요. 21 이 있는데도 다른 게 잡히면
`JAVA_HOME` 을 지정해서 실행하세요.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew bootRun --args='--spring.profiles.active=local'
```

---

## 5. 프로젝트 구조

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

## 6. 개발 흐름

**모든 작업은 이슈에서 시작합니다. 이슈 없이 브랜치를 따거나 PR 을 올리지 않습니다.**
작업 브랜치는 `dev` 에서 따고 `dev` 로 되돌립니다.

```
main   배포 가능한 상태만
 ↑
dev    통합 브랜치 ← 여기로 PR 을 올립니다
 ↑
feat/12-interview-session
```

```
1. GitHub → New issue → 기능 개발 / 버그 선택
2. dev 최신화        git switch dev && git pull
3. 브랜치 생성       git switch -c feat/12-interview-session   (12 = 이슈 번호)
4. 작업 + 커밋       feat(interview): 면접 세션 생성 API 추가
5. PR 생성           base 를 dev 로. 템플릿이 자동으로 채워짐
6. 본문에 Closes #12
7. 리뷰 1명 이상 승인 → Squash merge
```

커밋은 `{타입}({스코프}): {요약}` 형식입니다. 스코프는 건드린 도메인이나 계층
이름을 씁니다. (`interview` `ai` `storage` …)

`main` 과 `dev` 직접 푸시는 막혀 있습니다.
상세 규칙은 [`docs/01-conventions.md`](./docs/01-conventions.md) 의 작업 흐름 항목.

---

## 7. 문서

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
