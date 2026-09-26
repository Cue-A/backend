# 90. 미확정 사항

**이 목록에 있는 항목은 임의로 결정하지 않습니다.**
해당 영역 작업이 필요해지면 먼저 결정을 받으세요.

마지막 갱신: 2026-09-26

---

## AI 파트 회신 대기

| # | 항목 | 영향 | 현재 대응 |
|---|---|---|---|
| 4 | AI 서버 Dockerfile 제공 여부 | **compose 구성** | `python-ai` 서비스 미포함 |
| 6 | AI 서버 포트 | 설정값 | **8000 가정** |
| 7 | 헬스체크 엔드포인트 | 모니터링 | 없음 가정 |
| 8 | AI 환경변수 목록 | `.env.example` | 미반영 |
| 9 | 더미 서버 제공 일정 | 작업 순서 | 내부 목으로 대체 |
| 10 | S3 CORS를 도메인 한정으로 해도 되는지 | 버킷 설정 | 도메인 한정으로 진행 |

> RAG·벡터 저장소(구 1·2·3번), GPU 필요 여부(구 5번)는 AI 계약이 확정되어 아래
> "해결된 것" 으로 옮겼습니다. 번호는 이력 추적을 위해 그대로 둡니다.

### 대응 방침

**4번** — 도커화되지 않으면 배포 시 Spring만 컨테이너이고 AI는 별도로 도는
상태가 됩니다. 실제 AI 서버는 학과 GPU 서버에서 고정 HTTPS 주소로 도는 것으로
확정됐으므로([`10-ai-client.md`](./10-ai-client.md)), compose 통합보다 `APP_AI_BASE_URL`
로 그 주소를 가리키는 구성이 기본입니다. 데모용 통합이 필요하면 계속 요청하세요.

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
| 소셜·이메일 계정 연동 | 인증 수단을 `user_auth` 로 분리. 카카오→기존계정 연결 허용(이메일 검증된 경우만), 이메일가입→기존계정 연결 금지 | `02-database.md` |
| RAG / 벡터 저장소 사용 여부 (구 1·2·3번) | **사용 안 함.** RAG·FAISS·vector DB·인덱스 volume·AI 문서 인덱싱 엔드포인트 모두 폐기. 이력서 전체를 그대로 Claude 에 전달 | `10-ai-client.md` · `02-database.md` |
| GPU 필요 여부 (구 5번) | **필요.** Whisper·시선 분석용. 실제 AI 서버는 학과 GPU 서버에서 고정 HTTPS 주소로 운영. Backend 는 `APP_AI_BASE_URL` 로 연결 | `10-ai-client.md` |
| 실제 모드 스토리지 | **AWS S3.** 외부 GPU 서버가 로컬 MinIO 에 못 닿음. 이력서·답변은 presigned GET, 질문 음성은 AI 직접 PUT | `20-storage.md` |
| AI 요청 인증 | 공유 시크릿 헤더 `X-Cueanda-Secret` (구현 완료). `RealAiClient` 가 모든 나가는 요청에 적용 | `10-ai-client.md` |
| `doc_id` 의미·저장 시점 | RAG/인덱스 ID 아님. 향후 파싱 캐시 재사용용 예약 필드. 현재 항상 null, 저장 안 함. 기능 활성화 시 AI 가 먼저 계약 변경 | `10-ai-client.md` · `02-database.md` |
| STT/LLM 실패 전달 방식 | HTTP 500 아님. 폴링 `status:error` + `error_code` 로 옴 | `10-ai-client.md` |
| `TTS_FAILED` | task error 아님. TTS 실패 시 `status:done`, `text` 유지, `audio_url=null`. text-only 정상 처리 | `10-ai-client.md` |
| STT/LLM 재시도·세션 실패 정책 | 답변은 error 확인 후 동일 body 1회 재전송(새 task_id). STT 재실패=재녹음·세션 유지, LLM 재실패=ABORTED. 세션 시작 LLM 은 AI 내부 1회 재시도, 최종 실패 시 ABORTED | `10-ai-client.md` · `11-interview.md`(Issue #25) |

> 질문 음성 unsigned `audio_url` 의 private bucket 403 문제는 **Issue #41** 에서 별도
> 처리합니다. 아직 해결되지 않았으므로 여기 "해결된 것" 에 넣지 않습니다.
> [`20-storage.md`](./20-storage.md) 참고.
