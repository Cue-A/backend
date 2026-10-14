package com.cuea.infrastructure.file;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.util.Optional;

/**
 * 버킷에 올리고, 뭐가 있는지 확인하고, 지웁니다.
 *
 * <p><b>업로드 경로가 둘입니다.</b> 문서 파일은 {@link #put} 으로 이 서버를 통과해
 * 올라가고(Issue #28 — 최대 10MB 라 MVP 에서는 통과시켜도 된다는 판단),
 * 답변 녹음은 크기가 커서 계속 프론트가 Presigned URL 로 직접 올립니다.
 * 동시 업로드가 늘면 문서도 Presigned 로 되돌려야 합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService {

    private final S3Client s3Client;
    private final StorageProperties properties;

    /**
     * 파일을 버킷에 올립니다.
     *
     * <p>스트림을 그대로 흘려보냅니다. 10MB 를 바이트 배열로 들고 있으면 동시
     * 업로드가 몰릴 때 힙이 그만큼 배로 늘어납니다. S3 는 스트림 길이를 미리
     * 알아야 하므로 {@code size} 를 함께 받습니다.
     */
    /**
     * <b>{@code content} 를 닫지 않습니다.</b> AWS SDK 도 닫아주지 않으므로 여는 쪽이
     * try-with-resources 로 책임집니다. 여기서 닫으면 재시도 같은 상위 제어가 막힙니다.
     */
    public void put(String objectKey, InputStream content, long size, String contentType) {
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .contentType(contentType)
                            .contentLength(size)
                            .build(),
                    RequestBody.fromInputStream(content, size));
        } catch (S3Exception e) {
            log.error("S3 putObject 실패 key={}", objectKey, e);
            throw new BusinessException(ErrorCode.STORAGE_ERROR);
        }
    }

    /**
     * 업로드 완료 확인용. 프론트가 PUT 만 하고 complete 를 안 부르면 우리는 업로드
     * 성공 여부를 모릅니다. complete 시점에 이걸로 실제 객체 유무를 확인합니다.
     */
    public Optional<HeadObjectResponse> head(String objectKey) {
        try {
            return Optional.of(s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            log.error("S3 headObject 실패 key={}", objectKey, e);
            throw new BusinessException(ErrorCode.STORAGE_ERROR);
        }
    }

    public boolean exists(String objectKey) {
        return head(objectKey).isPresent();
    }

    public void delete(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
        } catch (S3Exception e) {
            log.error("S3 deleteObject 실패 key={}", objectKey, e);
            throw new BusinessException(ErrorCode.STORAGE_ERROR);
        }
    }
}
