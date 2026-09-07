# 20. 스토리지 (S3 / MinIO)

로컬은 MinIO, 배포는 S3. 코드는 AWS S3 SDK로 통일하고 엔드포인트만 바꿉니다.

---

## 버킷 구조

```
cue-a-media/
  resumes/{userId}/{documentId}.{ext}          프론트가 Presigned PUT
  sessions/{sessionId}/answers/{questionId}.webm   프론트가 Presigned PUT
  sessions/{sessionId}/questions/{questionId}.mp3  ★ AI가 직접 PUT
```

---

## 접근 권한

| 주체 | 권한 |
|---|---|
| 프론트 | Presigned URL로만 접근. 자격증명 없음 |
| AI 서버 | `sessions/*/questions/*` **쓰기 권한** |
| Spring | 전체 |

AI에 발급하는 자격증명은 **질문 오디오 경로에만** 쓰기 권한을 줍니다.
전체 버킷 쓰기 권한을 주지 않습니다.

로컬 개발에서는 MinIO 계정(`minioadmin`)을 공유해도 무방하지만,
배포 시에는 IAM 정책으로 경로를 제한합니다.

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

```
1. 프론트: POST /api/documents/presigned  (파일명, 크기, MIME)
2. Spring: 검증 후 Presigned PUT URL + documentId 반환
3. 프론트: 해당 URL로 S3에 직접 PUT
4. 프론트: POST /api/documents/{id}/complete
5. Spring: object_key 확정, DB 저장
```

**4번을 빼먹지 마세요.** 3번만으로는 Spring이 업로드 성공 여부를 모릅니다.
`complete` 호출 시 S3에 객체가 실제로 있는지 `headObject` 로 확인합니다.

### 파일 검증

| 항목 | 제한 |
|---|---|
| 이력서 | PDF, DOCX, DOC, TXT / 10MB |
| 답변 오디오 | webm, mp4 / 50MB |

MIME 타입은 클라이언트가 보낸 값을 믿지 않고 확장자와 함께 검증합니다.

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
