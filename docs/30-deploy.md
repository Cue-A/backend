# 30. 배포

스토리지는 [`20-storage.md`](./20-storage.md), AI 통신은 [`10-ai-client.md`](./10-ai-client.md).

---

## 구성

```
프론트 ──https/wss──▶ Spring (1대) ──https + X-Cueanda-Secret──▶ AI (학과 GPU 서버)
   │                     └──▶ PostgreSQL                          │
   └──presigned URL──▶ S3 cue-a-media ◀──presigned GET / TTS PUT──┘
```

AI 서버는 AI 파트가 운영합니다. 우리가 준비하는 것은 S3 와 백엔드(+DB)입니다.

---

## 순서

| # | 할 일 | 상태 |
|---|---|---|
| 1 | S3 버킷 `cue-a-media` (`ap-northeast-2`, 퍼블릭 차단) | ✅ |
| 2 | IAM 사용자 `cue-a-backend` · `cue-a-ai` | ✅ |
| 3 | S3 CORS | ✅ localhost · `frontend-hl9w.vercel.app` |
| 4 | AI 에 버킷·리전·`cue-a-ai` 키 전달 | |
| 5 | 질문 음성 presigned GET (Issue #54) | ✅ push 때 presigned GET 발급 |
| 6 | 실제 AI 연동 테스트 (로컬 백엔드 + S3) | |
| 7 | DB (백엔드에서만 접근) | 설정 완료 (Issue #72). EC2 준비 필요 |
| 8 | 백엔드 배포 (1대) | 설정 완료 (Issue #72). EC2 준비 필요 |
| 9 | 도메인 + HTTPS + WebSocket 프록시 | 설정 완료 (Issue #72). 도메인 구매 필요 |
| 10 | 재배포 공유 규칙 | |

1~6 은 백엔드 배포 없이 로컬에서 할 수 있습니다.

---

## 서버 구성

EC2 1대에 `deploy/docker-compose.prod.yml` 로 전부 띄웁니다.

```
인터넷 ──80/443──▶ nginx ──▶ spring:8080 ──▶ postgres · redis
                    └ certbot (인증서 갱신)
```

- 밖으로 열리는 것은 nginx(80 · 443)뿐입니다. DB · Redis · Spring 은 포트를 열지 않습니다.
- **Redis 는 빼면 안 됩니다.** refresh 토큰과 호출 제한이 씁니다.
- nginx 가 `/ws/` 에 `Upgrade` · `Connection` 헤더를 넘기고 read timeout 을 15분으로 둡니다.
  업로드 상한(`client_max_body_size`)은 Spring 과 같은 12MB 입니다.

| 파일 | 용도 |
|---|---|
| `deploy/docker-compose.prod.yml` | 운영 compose |
| `deploy/nginx/default.conf.template` | nginx. `${API_DOMAIN}` 만 치환됩니다 |
| `deploy/init-letsencrypt.sh` | 인증서 최초 발급 (한 번만) |
| `deploy/.env.prod.example` | 서버 `.env` 견본 |
| `.github/workflows/deploy.yml` | main 머지 시 배포 |

---

## 처음 한 번 — 서버 준비

1. **도메인.** 가비아에서 구매 → DNS 관리에서 `api` A 레코드를 EC2 탄력적 IP 로.
2. **EC2.** Ubuntu 24.04, **x86(t3.small 이상)**. t4g(ARM)는 이미지가 실행되지 않습니다.
   보안그룹 인바운드는 22 · 80 · 443 만. 탄력적 IP 를 붙입니다.
3. **Docker 설치.**
   ```bash
   curl -fsSL https://get.docker.com | sh
   sudo usermod -aG docker ubuntu   # 다시 로그인
   # t3.small 은 메모리가 빠듯해 스왑을 둡니다
   sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
   sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   ```
4. **GitHub Secrets** (저장소 Settings → Secrets and variables → Actions).

   | 이름 | 값 |
   |---|---|
   | `EC2_HOST` | 탄력적 IP |
   | `EC2_USER` | `ubuntu` |
   | `EC2_SSH_KEY` | EC2 키 페어 `.pem` 내용 전체 |

5. **첫 배포.** Actions → Deploy → Run workflow. 파일과 이미지가 `~/cue-a` 에 올라갑니다.
   `.env` 가 아직 없어 기동은 건너뜁니다(경고만 남깁니다).
6. **`.env` 작성.** `cd ~/cue-a && cp .env.prod.example .env` 후 값을 채웁니다.
7. **인증서 발급.** `nslookup api.<도메인>` 이 EC2 IP 를 가리키는지 확인한 뒤
   `./init-letsencrypt.sh`. 끝나면 전체가 기동됩니다.
8. **확인.** `https://api.<도메인>/swagger-ui.html` 이 열리면 됩니다.

---

## 배포 환경변수

서버 `~/cue-a/.env` 에만 있습니다. 견본은 `deploy/.env.prod.example`.
**배포 워크플로우는 `.env` 를 건드리지 않습니다.** 값을 바꾸면 서버에서 고치고
`docker compose -f docker-compose.prod.yml up -d` 로 재기동합니다.

| 변수 | 값 |
|---|---|
| `API_DOMAIN` · `CERTBOT_EMAIL` | `api.<도메인>` · 인증서 만료 알림 받을 메일 |
| `POSTGRES_PASSWORD` | `openssl rand -base64 24`. 데이터소스는 compose 가 맞춰줍니다 |
| `APP_STORAGE_ACCESS_KEY` · `_SECRET_KEY` | `cue-a-backend` 키 |
| `APP_AI_BASE_URL` · `APP_AI_SECRET` | AI 팀장에게 받은 값 |
| `APP_JWT_SECRET` | `openssl rand -base64 48`. 기본값이면 기동 실패 |
| `APP_OAUTH_KAKAO_CLIENT_ID` · `_SECRET` | 카카오 콘솔 값. 기본값이면 기동 실패 |
| `APP_CORS_ALLOWED_ORIGINS` | 프론트 도메인. API 와 WebSocket 이 같이 씁니다 |

`SPRING_PROFILES_ACTIVE=docker` 와 `SERVER_FORWARD_HEADERS_STRATEGY=framework` 는
compose 에 고정돼 있습니다.

---

## 재배포

`dev → main` PR 을 머지하면 Actions 가 테스트 → 이미지 빌드 → EC2 로 전송 → 재기동합니다.
테스트가 깨지면 배포하지 않습니다. 수동으로는 Actions → Deploy → Run workflow.

```bash
# 서버에서 로그 보기
cd ~/cue-a && docker compose -f docker-compose.prod.yml logs -f spring
```

---

## 주의

- **백엔드·AI 모두 인스턴스 1개.** WebSocket 연결과 AI 세션이 프로세스 메모리에 있습니다.
- **WebSocket 프록시.** nginx 는 `Upgrade` · `Connection` 헤더를 넘기고, idle timeout
  을 기본 60초보다 늘립니다. 리포트 분석이 최대 10분입니다.
- **분석 중 재배포하면 리포트가 `PROCESSING` 에 남습니다.** 기동 시 정리하는 처리가
  아직 없습니다. `FAILED` 로 돌릴지 `ai_task_id` 로 폴링을 재개할지 정해야 합니다.
- **재배포는 미리 공유.** AI 재배포는 진행 중 세션을 날리고(`SESSION_NOT_FOUND`),
  백엔드 재배포는 폴링을 끊습니다.
