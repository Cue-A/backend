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
| 3 | S3 CORS | ✅ localhost 만. 프론트 도메인 추가 필요 |
| 4 | AI 에 버킷·리전·`cue-a-ai` 키 전달 | |
| 5 | 질문 음성 presigned GET (Issue #41) | |
| 6 | 실제 AI 연동 테스트 (로컬 백엔드 + S3) | |
| 7 | DB (백엔드에서만 접근) | |
| 8 | 백엔드 배포 (1대) | |
| 9 | 도메인 + HTTPS + WebSocket 프록시 | |
| 10 | 재배포 공유 규칙 | |
| 나중 | Redis | 인스턴스를 늘릴 때만 |

1~6 은 백엔드 배포 없이 로컬에서 할 수 있습니다.

---

## 배포 환경변수

`.env.example` 기준으로 달라지는 것만 적습니다.

| 변수 | 값 |
|---|---|
| `SPRING_DATASOURCE_*` | 배포 DB |
| `APP_STORAGE_ENDPOINT` | `https://s3.ap-northeast-2.amazonaws.com` |
| `APP_STORAGE_REGION` | `ap-northeast-2` |
| `APP_STORAGE_PATH_STYLE_ACCESS` | `false` |
| `APP_STORAGE_ACCESS_KEY` · `_SECRET_KEY` | `cue-a-backend` 키 |
| `APP_AI_BASE_URL` · `APP_AI_SECRET` | AI 팀장에게 받은 값 |
| `APP_AI_MOCK_ENABLED` | `false` |
| `APP_JWT_SECRET` | `openssl rand -base64 48` |
| `APP_CORS_ALLOWED_ORIGINS` | 프론트 도메인 |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` (프록시 뒤일 때) |

---

## 주의

- **백엔드·AI 모두 인스턴스 1개.** WebSocket 연결과 AI 세션이 프로세스 메모리에 있습니다.
- **WebSocket 프록시.** nginx 는 `Upgrade` · `Connection` 헤더를 넘기고, idle timeout
  을 기본 60초보다 늘립니다. 리포트 분석이 최대 10분입니다.
- **분석 중 재배포하면 리포트가 `PROCESSING` 에 남습니다.** 기동 시 정리하는 처리가
  아직 없습니다. `FAILED` 로 돌릴지 `ai_task_id` 로 폴링을 재개할지 정해야 합니다.
- **재배포는 미리 공유.** AI 재배포는 진행 중 세션을 날리고(`SESSION_NOT_FOUND`),
  백엔드 재배포는 폴링을 끊습니다.
