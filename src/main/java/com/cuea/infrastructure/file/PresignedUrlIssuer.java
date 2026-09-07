package com.cuea.infrastructure.file;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

/**
 * Presigned URL 발급.
 *
 * <p>발급한 URL 을 DB 에 저장하지 마세요. 만료됩니다.
 * object_key 만 저장하고 필요할 때 다시 만듭니다.
 */
@Component
@RequiredArgsConstructor
public class PresignedUrlIssuer {

    private final S3Presigner presigner;
    private final StorageProperties properties;

    /** 업로드용 PUT. 프론트가 이 URL 로 S3 에 직접 올립니다. */
    public String issueUpload(String objectKey, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();

        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(properties.presign().uploadPut())
                        .putObjectRequest(put)
                        .build())
                .url()
                .toString();
    }

    /**
     * AI 에 넘길 이력서 GET.
     *
     * <p>만료가 15분인 이유: 이력서 파싱은 AI 의 세션 시작 태스크 안에서 일어나고,
     * Celery 큐가 밀리면 발급 시점과 사용 시점 사이에 간격이 생깁니다. 짧게 잡으면
     * RESUME_PARSE_FAILED 가 간헐적으로 뜨는데 재현이 안 돼 추적이 매우 어렵습니다.
     */
    public String issueResumeDownload(String objectKey) {
        return issueDownload(objectKey, properties.presign().resumeGet());
    }

    /** 사용자가 리포트를 보며 재생하는 녹음. */
    public String issueRecordingDownload(String objectKey) {
        return issueDownload(objectKey, properties.presign().recordingGet());
    }

    private String issueDownload(String objectKey, Duration validity) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .build();

        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(validity)
                        .getObjectRequest(get)
                        .build())
                .url()
                .toString();
    }
}
