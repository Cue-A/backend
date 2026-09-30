package com.cuea.infrastructure.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 질문 음성 presigned GET 발급의 인프라 경계를 검증합니다. (Issue #41, PR #54 리뷰 반영)
 *
 * <p>AWS SDK 예외를 <b>인프라 계층 안에서</b> 캡슐화하는지 여기서만 확인합니다.
 * 도메인({@code QuestionPushFactory})은 AWS SDK 타입을 알지 못하고 {@link Optional} 만
 * 봅니다.
 * <ul>
 *   <li>발급 성공 → {@code Optional.of(url)}</li>
 *   <li>{@link SdkException}(자격증명·서명·설정 실패) 발생 → 예외를 전파하지 않고
 *       {@code Optional.empty()}</li>
 * </ul>
 */
class PresignedUrlIssuerTest {

    private static final String OBJECT_KEY = "sessions/sess_1/questions/q_1.mp3";

    private S3Presigner presigner;
    private PresignedUrlIssuer issuer;

    @BeforeEach
    void setUp() {
        presigner = mock(S3Presigner.class);
        StorageProperties properties = new StorageProperties(
                "http://localhost:9000", "cue-a-media", "minioadmin", "minioadmin",
                "us-east-1", true,
                new StorageProperties.Presign(
                        Duration.ofMinutes(15), Duration.ofMinutes(10),
                        Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(15)));
        issuer = new PresignedUrlIssuer(presigner, properties);
    }

    @Test
    void 질문음성_presign_성공하면_Optional_of_로_서명URL_을_돌려준다() {
        // presigner 가 서명 URL 이 담긴 요청을 돌려주는 정상 상황.
        URI signedUri = URI.create(
                "http://localhost:9000/cue-a-media/" + OBJECT_KEY + "?X-Amz-Signature=abc");
        PresignedGetObjectRequest presigned = PresignedGetObjectRequest.builder()
                .expiration(Instant.now().plusSeconds(900))
                .isBrowserExecutable(true)
                .signedHeaders(java.util.Map.of("host", java.util.List.of("localhost")))
                .httpRequest(SdkHttpRequest.builder()
                        .method(SdkHttpMethod.GET)
                        .uri(signedUri)
                        .build())
                .build();
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        Optional<String> result = issuer.issueQuestionAudioDownload(OBJECT_KEY);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("X-Amz-Signature");
    }

    @Test
    void 질문음성_presign_이_SdkException_이면_예외를_전파하지_않고_Optional_empty_를_돌려준다() {
        // AWS SDK 서명 실패 계열(자격증명·설정 오류 등)은 SdkException 으로 온다.
        // 인프라가 이를 캡슐화해 도메인으로 예외를 전파하지 않고 empty 를 돌려줘야 한다.
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(SdkException.builder().message("presign failed").build());

        Optional<String> result = issuer.issueQuestionAudioDownload(OBJECT_KEY);

        assertThat(result).isEmpty();
    }
}
