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
     * @param resumeGet    이력서 GET(AI 전달용). Celery 큐 지연 대비로 15분. 줄이지 마세요
     * @param uploadPut    업로드 PUT
     * @param recordingGet 녹음 다운로드 GET. 사용자가 리포트를 보며 재생합니다
     */
    public record Presign(
            Duration resumeGet,
            Duration uploadPut,
            Duration recordingGet
    ) {
    }
}
