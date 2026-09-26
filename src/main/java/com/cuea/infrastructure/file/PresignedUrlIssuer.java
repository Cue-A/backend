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

    /**
     * 사용자가 자기 문서를 다시 볼 때 쓰는 GET.
     *
     * <p>{@link #issueResumeDownload} 와 나눈 이유는 <b>만료를 따로 잡아야 하기
     * 때문</b>입니다. 그쪽 15분은 AI 의 Celery 큐 지연에 맞춘 값이고, 이쪽은
     * 사람이 브라우저에서 열어보는 시간입니다. 하나로 합치면 한쪽 사정 때문에
     * 다른 쪽이 끌려다닙니다.
     */
    public String issueDocumentDownload(String objectKey) {
        return issueDownload(objectKey, properties.presign().documentGet());
    }

    /** 사용자가 리포트를 보며 재생하는 녹음. */
    public String issueRecordingDownload(String objectKey) {
        return issueDownload(objectKey, properties.presign().recordingGet());
    }

    /**
     * 질문 TTS 음성 GET.
     *
     * <p>AI 가 private 버킷의 {@code sessions/{sessionId}/questions/{questionId}.mp3}
     * 에 올린 음성을, WebSocket 질문 push 시점에 서명해 프론트에 내려줍니다. 버킷을
     * public 으로 열지 않고 이 서명 URL 로만 재생하게 합니다. object key 는 AI 계약상
     * 고정 규칙이라 {@link ObjectKeys#questionAudio} 로 생성하며, AI 가 준 URL 을
     * parsing 하지 않습니다(엔드포인트·path-style 차이로 취약).
     */
    public String issueQuestionAudioDownload(String objectKey) {
        return issueDownload(objectKey, properties.presign().questionAudioGet());
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
