package com.cuea.common.config;

import com.cuea.infrastructure.file.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * 로컬은 MinIO, 배포는 S3. 코드는 AWS SDK 로 통일하고 엔드포인트만 바꿉니다.
 *
 * <p>{@code path-style-access} 를 켜지 않으면 MinIO 가 버킷을 서브도메인으로
 * 해석해 연결이 실패합니다. 실제 S3 로 가면 false 로 둡니다.
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final StorageProperties properties;

    @Bean
    S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials())
                .serviceConfiguration(serviceConfiguration())
                .build();
    }

    @Bean
    S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials())
                .serviceConfiguration(serviceConfiguration())
                .build();
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }

    private S3Configuration serviceConfiguration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleAccess())
                .build();
    }
}
