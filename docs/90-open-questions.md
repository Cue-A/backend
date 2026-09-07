# 90. 미확정 사항

**이 목록에 있는 항목은 임의로 결정하지 않습니다.**
해당 영역 작업이 필요해지면 먼저 결정을 받으세요.

마지막 갱신: 2026-09-05

---

## AI 파트 회신 대기

| # | 항목 | 영향 | 현재 대응 |
|---|---|---|---|
| 1 | RAG 사용 여부 | — | 백엔드 영향 없음 |
| 2 | 벡터 저장소 종류 | — | — |
| 3 | pgvector면 PostgreSQL 공유 여부 | **compose 이미지** | `postgres:16` 으로 진행 |
| 4 | AI 서버 Dockerfile 제공 여부 | **compose 구성** | `python-ai` 서비스 미포함 |
| 5 | GPU 필요 여부 (Whisper·TTS 로컬 실행 여부) | compose 설정 | GPU 없음 가정 |
| 6 | AI 서버 포트 | 설정값 | **8000 가정** |
| 7 | 헬스체크 엔드포인트 | 모니터링 | 없음 가정 |
| 8 | AI 환경변수 목록 | `.env.example` | 미반영 |
| 9 | 더미 서버 제공 일정 | 작업 순서 | 내부 목으로 대체 |
| 10 | S3 CORS를 도메인 한정으로 해도 되는지 | 버킷 설정 | 도메인 한정으로 진행 |

### 대응 방침

**3번** — pgvector가 필요해지면 compose의 이미지를 `pgvector/pgvector:pg16` 으로
바꾸고 `CREATE EXTENSION vector` 초기화 스크립트를 추가합니다. 한 줄 변경이라
지금 기다릴 필요 없습니다. 벡터 테이블은 AI 파트 소유이며 **Spring은 읽지도 쓰지도
않습니다.** JPA 엔티티를 만들지 마세요.

**4·5번** — 도커화되지 않으면 배포 시 Spring만 컨테이너이고 AI는 별도로 도는
상태가 됩니다. 데모 직전 환경 불일치 위험이 있으니 계속 요청하세요.

**9번** — 내부 `MockAiClient` 로 대체합니다. [`10-ai-client.md`](./10-ai-client.md)
의 개발용 목 참고. 이것 덕분에 AI 서버 없이도 2단계 작업을 진행할 수 있습니다.

---

## 리포트 계약

AI 파트 작성 중. **9/3 목요일 회의 전 전달 예정.**

도착 전까지 `report`, `growth` 모듈의 엔티티를 만들지 마세요.
확인할 항목은 [`13-report.md`](./13-report.md) 참고.

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
| `audio_url` | AI가 S3 직접 업로드 | `20-storage.md` |
| `question_id` 스코프 | 세션 스코프, 복합키 | `02-database.md` |
| `reask` 응답 필드 | 두 플래그 항상 포함, 둘 다 false | `11-interview.md` |
| 되묻기 한도 | 토픽당 1회 / 세션당 3회 | `11-interview.md` |
| Presigned 만료 | 이력서 15분 | `20-storage.md` |
| `STT_FAILED` 재시도 | 1회 후 재녹음 안내 | `10-ai-client.md` |
| 회사 목록 캐시 | Redis 1시간 | `10-ai-client.md` |
| 세션 상태 소유권 | AI가 보유. 표시 상태는 Spring DB 기준 | `10-ai-client.md` |
| 리포트 부분 실패 | 내용 실패=전체 실패 / 음성·시선=해당 축만 | `13-report.md` |
