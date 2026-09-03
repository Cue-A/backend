package com.cuea.infrastructure.file;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Optional;

/**
 * 버킷에 실제로 뭐가 있는지 확인하고 지웁니다. 파일 바이트는 이 서버를 지나가지
 * 않습니다. 업로드·다운로드는 프론트가 Presigned URL 로 직접 합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService {

    private final S3Client s3Client;
    private final StorageProperties properties;

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
