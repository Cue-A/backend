package com.cuea.infrastructure.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 로컬은 MinIO, 배포는 S3.
 *
 * @param pathStyleAccess MinIO 는 true 필수. 끄면 버킷을 서브도메인으로 해석해 실패합니다
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String region,
        boolean pathStyleAccess,
        Presign presign
) {

    /**
     * @param resumeGet        이력서 GET(AI 전달용). Celery 큐 지연 대비로 15분. 줄이지 마세요
     * @param uploadPut        업로드 PUT
     * @param recordingGet     녹음 다운로드 GET. 사용자가 리포트를 보며 재생합니다
     * @param documentGet      사용자가 자기 문서를 다시 볼 때 쓰는 GET
     * @param questionAudioGet 질문 TTS 음성 GET. AI 가 private 버킷에 올린 질문 음성을
     *                         WebSocket 질문 push 시 프론트에 서명해 내려줍니다. 사용자가
     *                         한 문항을 듣는 시간이라 짧게 잡습니다
     */
    public record Presign(
            Duration resumeGet,
            Duration uploadPut,
            Duration recordingGet,
            Duration documentGet,
            Duration questionAudioGet
    ) {
    }
}
