# 20. 스토리지 (S3 / MinIO)

로컬은 MinIO, 배포는 S3. 코드는 AWS S3 SDK로 통일하고 엔드포인트만 바꿉니다.

## 로컬 MinIO vs 실제 모드 S3

**실제 모드에서는 AWS S3 를 씁니다.** 실제 AI 서버는 외부 GPU 서버에서 돌기 때문에
로컬 MinIO 주소(`localhost:9000`)에 접근할 수 없습니다. AI 가 이력서·답변 미디어를
직접 다운로드하고 질문 음성을 직접 업로드하려면 외부에서 닿는 S3 가 필요합니다.

| | 로컬/더미 | 실제 모드 |
|---|---|---|
| 스토리지 | MinIO (`docker-compose.dev.yml`) | AWS S3 |
| `path-style-access` | `true` (필수) | `false` |
| AI 접근 | 없음(mock) 또는 같은 호스트 | 외부 GPU 서버 → S3 직접 |
| 자격증명 | `minioadmin` 공유 | IAM (경로 제한) |

버킷 이름·리전·실제 IAM 자격증명은 운영 설정이며 **문서에 값을 적지 않습니다.**
설정 키는 `app.storage.*`(`APP_STORAGE_*` 환경변수)입니다.

---

## 버킷 구조

```
cue-a-media/
  resumes/{userId}/{documentId}.{ext}                    Spring 이 PUT (문서 등록)
  sessions/{sessionId}/answers/{questionId}.{ext}        프론트가 Presigned PUT (답변 오디오)
  sessions/{sessionId}/answers/{questionId}_video.{ext}  프론트가 Presigned PUT (답변 영상)
  sessions/{sessionId}/questions/{questionId}.mp3        ★ AI가 직접 PUT
```

### 문서 파일 삭제

문서 삭제(`DELETE /api/documents/{documentId}`)는 DB 에서 문서를 숨기고 **S3 파일도
항상 지웁니다.** 면접에 쓰인 문서라도 남기지 않으므로, 그 면접은 재연습할 수 없습니다
(Issue #38, [`02-database.md`](./02-database.md) 의 소프트 삭제 절).

S3 삭제가 실패해도 요청은 성공으로 끝납니다. DB 에서는 이미 숨겼고, 남은 파일은
로그(`S3 파일 삭제 실패`)로 추적합니다.
### 문서 key 규칙

파일 문서와 마크다운 문서가 같은 `resumes/{userId}/{documentId}.{ext}` 를 씁니다.

- **파일 문서**: 올린 원본. 확장자는 원본 그대로(`pdf` · `docx` · `txt`)
- **마크다운 문서**: 본문의 사본. 항상 `.txt`, `text/plain; charset=UTF-8`(Issue #36)

마크다운 사본을 따로 두는 이유는 면접 시작이 AI 에 파일 URL 만 넘기기 때문입니다.
원본은 DB 의 `doc_text` 이고, 사본은 AI 가 읽어가는 용도입니다.
[`02-database.md`](./02-database.md) 의 문서 절 참고.

### 답변 미디어 key 규칙

답변 오디오·영상은 같은 `sessions/{sessionId}/answers/{questionId}` 계층에 놓입니다.

- **오디오**(필수): `sessions/{sessionId}/answers/{questionId}.{ext}`
- **영상**(선택, 카메라 미사용이면 없음): `sessions/{sessionId}/answers/{questionId}_video.{ext}`

**영상 파일명에 `_video` 접미사를 붙여 오디오와 분리합니다.** 오디오·영상 컨테이너가
같을 수 있어(둘 다 `webm` 가능) 확장자만으로는 key 가 겹치기 때문입니다.

확장자는 실제 업로드 포맷을 그대로 씁니다(고정하지 않음). 허용 포맷과 MIME 은
아래 "파일 검증" 을 따릅니다. **object key 는 `sessionId`·`questionId` 로 서버가
만들며, 프론트가 임의 key 를 보내 다른 경로의 Presigned URL 을 얻을 수 없습니다.**
답변 업로드 URL 발급(`POST /api/interviews/{sessionId}/answers/upload-urls`) 전에
해당 `(sessionId, questionId)` 질문이 실제 존재하는지 확인하고, 답변 제출
(`POST /api/interviews/{sessionId}/answers`) 시에는 제출된 key 가 그 질문의 정규
namespace 에 속하는지 + S3 에 실제 업로드됐는지(`headObject`) 확인합니다.

---

## 접근 권한

실제 모드에서 AI 가 S3 를 다루는 방식은 세 가지입니다.

| 대상 | 발급/주체 | 방향 |
|---|---|---|
| 이력서 | Backend 가 presigned GET 발급 | AI 가 URL 로 직접 다운로드 |
| 답변 오디오·영상 | Backend 가 presigned GET 발급 | AI 가 URL 로 직접 다운로드 |
| 질문 음성 | AI 가 S3 에 직접 업로드 | `sessions/{sessionId}/questions/{questionId}.mp3` |

이력서·답변 미디어는 Backend 가 만료 있는 presigned GET 을 발급하므로 AI 에 상시
자격증명이 필요 없습니다. **질문 음성만 AI 가 직접 PUT** 하므로 여기에만 쓰기
자격증명이 필요합니다.

| 주체 | 권한 |
|---|---|
| 프론트 | Presigned URL로만 접근. 자격증명 없음 |
| AI 서버 | `sessions/*/questions/*` **쓰기 전용** (최소 권한) |
| Spring | 전체 |

AI 에 발급하는 IAM 자격증명은 **질문 오디오 경로(`sessions/*/questions/*`) 쓰기
권한만** 주는 최소 권한으로 발급할 예정입니다. 전체 버킷 쓰기 권한이나 읽기 권한을
주지 않습니다. 이력서·답변 미디어 읽기는 presigned GET 으로 충분하기 때문입니다.

로컬 개발에서는 MinIO 계정(`minioadmin`)을 공유해도 무방하지만, 실제 모드에서는
IAM 정책으로 경로를 제한합니다. **실제 access key·secret 값은 문서에 적지 않습니다.**

### 질문 음성 재생 문제는 Issue #41 (이번 범위 밖)

AI 는 현재 질문 음성에 대해 **서명되지 않은 `audio_url`** 을 반환합니다. 버킷이
private 이면 프론트가 그 URL 로 직접 재생할 때 403 이 날 수 있습니다. Backend 가
질문 음성용 presigned GET 을 발급하는 방식으로 바꾸는 작업은 **별도 Issue #41** 에서
처리합니다. 이 문서는 현재 구조(AI 직접 업로드 + unsigned URL)와 별도 이슈가 있다는
사실만 기록하며, 해결된 것처럼 쓰지 않습니다.

---

## Presigned URL 만료

| 용도 | 만료 | 이유 |
|---|---|---|
| 이력서 GET (AI 전달용) | **15분** | Celery 큐 지연 대비 |
| 업로드 PUT | 10분 | |
| 녹음 다운로드 GET | 1시간 | 사용자가 리포트 보며 재생 |

### ★ 이력서 URL 만료에 여유를 두는 이유

이력서 파싱은 AI의 세션 시작 태스크 안에서 일어납니다. Celery 큐가 밀리면
URL 발급 시점과 실제 사용 시점 사이에 간격이 생깁니다. 만료가 짧으면
`RESUME_PARSE_FAILED` 가 간헐적으로 발생하고, **재현이 안 되는 유형이라
원인 추적이 매우 어렵습니다.**

AI 파트가 10분 이상을 요청했고, 여유를 두어 15분으로 정했습니다.

### Presigned URL을 DB에 저장하지 않습니다

만료되기 때문입니다. `object_key` 만 저장하고 필요할 때 발급합니다.
[`02-database.md`](./02-database.md) 의 문서 테이블 참고.

---

## CORS

### 왜 필요한가

프론트가 질문 오디오를 S3에서 직접 재생하는데, **Web Audio API로 음량을 읽어
아바타 입 움직임을 만듭니다.** CORS 헤더가 없으면 오디오는 재생되지만
음량이 무음으로 읽혀서 입이 안 움직입니다.

**로컬에서는 정상이고 배포 후에만 실패하는 유형**이라 처음부터 설정합니다.

### 설정

AI 파트는 `Access-Control-Allow-Origin: *` 를 요청했으나,
**프론트 도메인만 허용하는 것을 권장**합니다. 기능은 동일하고 버킷을 전 세계에
열어둘 이유가 없습니다.

```json
[
  {
    "AllowedOrigins": [
      "http://localhost:5173",
      "https://cue-a.example.com"
    ],
    "AllowedMethods": ["GET", "PUT"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }
]
```

> AI 파트에 이 방식으로 괜찮은지 확인이 필요합니다.
> [`90-open-questions.md`](./90-open-questions.md) 참고.

MinIO에서는 `mc` 로 설정합니다.

```bash
mc admin config set local api cors_allow_origin="http://localhost:5173"
```

---

## 업로드 흐름

### 문서(이력서·포트폴리오) — Spring 을 통과 (Issue #28)

문서는 presigned 2단계가 아니라 **`POST /api/documents` 로 파일을 Spring 에
통과시킵니다.**

```
1. 프론트: POST /api/documents  (multipart: 파일 + 메타)
2. Spring: 검증 → S3 에 PUT → DB 저장 → 즉시 READY
```

자소서는 최대 10MB 라 통과가 감당되고, presigned 로 나누면 PUT 은 성공했는데 확정
전에 브라우저가 닫힐 때 버킷에 고아 객체가 남기 때문입니다. 판단 근거는 Issue #28,
[`02-database.md`](./02-database.md) 의 문서 절 참고. 마크다운 문서도 본문을 `.txt`
사본으로 이 경로에서 함께 올립니다(Issue #36).

### 답변 미디어 — Presigned PUT

녹화·녹음은 크기가 커 계속 presigned 로 갑니다.

```
1. 프론트: POST /api/interviews/{sessionId}/answers/upload-urls  (questionId 등)
2. Spring: (sessionId, questionId) 질문 존재 확인 → Presigned PUT URL 발급
3. 프론트: 해당 URL 로 S3 에 직접 PUT (오디오·영상)
4. 프론트: POST /api/interviews/{sessionId}/answers  (제출)
5. Spring: 제출된 key 가 정규 namespace 인지 + S3 에 실제 있는지(headObject) 확인
```

object key 는 서버가 `sessionId`·`questionId` 로 만들므로, 프론트가 임의 key 로 다른
경로의 URL 을 얻을 수 없습니다.

### 파일 검증

| 항목 | 확장자 | MIME | 크기 |
|---|---|---|---|
| 이력서 | PDF, DOCX, DOC, TXT | 문서 MIME | 10MB |
| 답변 오디오 | webm, mp4 | `audio/webm`, `audio/mp4` | 50MB |
| 답변 영상 | webm, mp4 | `video/webm`, `video/mp4` | 50MB |

MIME 타입은 클라이언트가 보낸 값을 믿지 않고 확장자와 함께 검증합니다.
**답변 오디오와 영상은 같은 컨테이너(webm, mp4)를 쓰되 MIME 으로 역할을 구분합니다.**
오디오 자리에는 `audio/*`, 영상 자리에는 `video/*` 만 허용해, 오디오 파일이 영상
자리에 올라오는(또는 그 반대) 것을 막습니다. `FileValidator.validateAnswerAudio` /
`validateAnswerVideo` 가 각각 담당합니다.

---

## 로컬 MinIO

`docker-compose.dev.yml` 의 `createbuckets` 서비스가 기동 시 버킷을 자동
생성합니다. 콘솔에서 직접 만들 필요 없습니다.

| 항목 | 값 |
|---|---|
| API | http://localhost:9000 |
| 콘솔 | http://localhost:9001 |
| 계정 | `minioadmin` / `minioadmin` |
| 버킷 | `cue-a-media` |

### 설정

```yaml
app:
  storage:
    endpoint: ${APP_STORAGE_ENDPOINT:http://localhost:9000}
    bucket: ${APP_STORAGE_BUCKET:cue-a-media}
    access-key: ${APP_STORAGE_ACCESS_KEY}
    secret-key: ${APP_STORAGE_SECRET_KEY}
    path-style-access: true    # MinIO는 필수. S3로 가면 false
```

`path-style-access` 를 켜지 않으면 MinIO에서 버킷을 서브도메인으로 해석해
연결이 실패합니다.
