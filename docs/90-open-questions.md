# 90. 미확정 사항

**이 목록에 있는 항목은 임의로 결정하지 않습니다.**
해당 영역 작업이 필요해지면 먼저 결정을 받으세요.

마지막 갱신: 2026-10-01

---

## AI 파트 회신 대기

| # | 항목 | 영향 | 현재 대응 |
|---|---|---|---|
| 8 | AI 환경변수 목록 | `.env.example` | 미반영 |
| 9 | 더미 서버 제공 일정 | 작업 순서 | 내부 목으로 대체 |
| 10 | S3 CORS를 도메인 한정으로 해도 되는지 | 버킷 설정 | 도메인 한정으로 진행 |

> RAG·벡터 저장소(구 1·2·3번)와 헬스체크 엔드포인트(구 7번)는 AI 계약이 확정돼 아래
> "해결된 것" 으로 옮겼습니다. 번호는 이력 추적을 위해 그대로 둡니다.

### 대응 방침

**9번** — 내부 `MockAiClient` 로 대체합니다. [`10-ai-client.md`](./10-ai-client.md)
의 개발용 목 참고. 이것 덕분에 AI 서버 없이도 2단계 작업을 진행할 수 있습니다.

---

## 리포트 계약

**도착했습니다**(「리포트생성 API계약」). 생성 흐름은 Issue #47 에서 반영했고
[`13-report.md`](./13-report.md) 에 정리했습니다.

계약서 11장에 남은 미확정 항목은 **값의 문제라 구조에 영향이 없습니다.** Backend 는
AI 결과를 `report.report_data` 에 원본 그대로 저장하므로 기다릴 필요가 없습니다.

| 항목 | 확정 시기 |
|---|---|
| `metrics` 세부 필드 | 축별 지표 확정 후 |
| 축 가중치 (0.5 / 0.3 / 0.2) | 앵커 답변 세트 검증 후 |
| 게이트 임계값·상한값 | 앵커 답변 세트 검증 후 |
| `resilience` 산출식 | 압박형 실제 세션 확보 후 |

---

## 해결된 것 (기록)

| 항목 | 결론 | 반영 위치 |
|---|---|---|
| 폴링 주체 | Spring | `00-architecture.md` |
| 타임아웃 | 세션 시작 90초 / 답변 60초 | `10-ai-client.md` |
| 재연습 데이터 전달 | `replay_log` 배열. 진실 소스는 백엔드 DB | `12-replay.md` |
| 가상 경쟁자 (다대다 면접) | **MVP 범위에서 제외.** 구현하지 않습니다 | — |
| 꼬리질문 `difficulty` | 포함 필수 | `12-replay.md` |
| `retry_of_session_id` | 항상 최초 세션 | `12-replay.md` |
| 회차 비교 필터 | `is_replay` 만 사용 | `12-replay.md` |
| `audio_url` (질문 음성) | AI 가 서명 없는 URL 반환 → Backend 가 push 때 presigned GET 발급(private 유지). 실패 시 text-only (#54) | `20-storage.md` · `10-ai-client.md` |
| `question_id` 스코프 | 세션 스코프, 복합키 | `02-database.md` |
| `reask` 응답 필드 | 두 플래그 항상 포함, 둘 다 false | `11-interview.md` |
| 되묻기 한도 | 토픽당 1회 / 세션당 3회 | `11-interview.md` |
| Presigned 만료 | 이력서 15분 · 질문 음성 15분 | `20-storage.md` |
| `STT_FAILED` 재시도 | 1회 후 재녹음 안내 | `10-ai-client.md` |
| 회사 목록 캐시 | Redis 1시간 | `10-ai-client.md` |
| 세션 상태 소유권 | AI가 보유. 표시 상태는 Spring DB 기준 | `10-ai-client.md` |
| 리포트 부분 실패 | 내용 실패=전체 실패 / 음성·시선=해당 축만 | `13-report.md` |
| AI 서버 위치 (구 4·5·6번) | Whisper·시선 분석에 GPU 가 필요해 **실제 모드 AI 는 학과 GPU 서버**에서 상시 운영. Cloudflare `https` 고정 주소로 받아 `APP_AI_BASE_URL` 에 넣음. compose 에 AI 를 넣지 않고 포트도 신경 쓰지 않음 | `00-architecture.md`, `10-ai-client.md` |
| 실제 모드 스토리지 | 학과 서버가 노트북 MinIO 에 닿지 못하므로 **실제 AI 연동은 S3**. 버킷 `cue-a-media` / `ap-northeast-2` | `20-storage.md` |
| RAG / 벡터 저장소 (구 1·2·3번) | **사용 안 함.** RAG·FAISS·vector DB·AI 문서 인덱싱 엔드포인트 모두 폐기. 이력서 전체를 그대로 AI 에 전달 | `10-ai-client.md` · `02-database.md` |
| 헬스체크 엔드포인트 (구 7번) | AI 서버에 `/health`·`/ready` 존재. `X-Cueanda-Secret` 검증 예외 | `10-ai-client.md` |
| AI 요청 인증 | 공유 시크릿 헤더 `X-Cueanda-Secret`. `/health`·`/ready` 만 예외 | `10-ai-client.md` |
| STT/LLM 실패 전달·재시도 (#43) | HTTP 500 아님. 폴링 `status:error`. 답변 STT/LLM 은 1회 재전송, 세션 시작 LLM 은 재시도 없이 ABORTED | `10-ai-client.md` · `11-interview.md` |
| `TTS_FAILED` | task error 아님. `status:done` + `text` 유지 + `audio_url=null` → text-only | `10-ai-client.md` |
| 세션 abort·상태 역전 방지 (#43) | `POST /api/interviews/{sessionId}/abort` (멱등). IN_PROGRESS 에서만 전이. abort 후 늦은 결과 무시 | `11-interview.md` |
| `doc_id` | RAG/인덱스 id 아님. 예약 필드, 현재 항상 null·미저장 | `10-ai-client.md` |
| 외부 API 문서 식별자 (#46) | `documentId` (문서 API 와 통일). 이전 `documentPublicId` 폐기 | `11-interview.md` |
| 소셜·이메일 계정 연동 | 인증 수단을 `user_auth` 로 분리. 카카오→기존계정 연결 허용(이메일 검증된 경우만), 이메일가입→기존계정 연결 금지 | `02-database.md` |

---

## 아직 미해결 (구현 전)

이 항목들은 **아직 해결되지 않았습니다.** 문서 정리를 이유로 해결된 것처럼 쓰지 마세요.

| 항목 | 상태 | 참고 |
|---|---|---|
| 첫 질문 push 유실 | 미해결. 백그라운드 폴링이 WebSocket 핸드셰이크보다 먼저 끝나면 첫 질문 push 가 수신자 없이 드롭될 수 있음. pending 버퍼/catch-up 엔드포인트 미구현 | Issue #3/#35, `11-interview.md` |
| 재연습(replay) | MVP 이후 별도 작업. 미구현 | Issue #26, `12-replay.md` |
