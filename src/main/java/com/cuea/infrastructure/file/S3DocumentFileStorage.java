package com.cuea.infrastructure.file;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * {@link DocumentFileStorage} 의 S3(로컬은 MinIO) 구현.
 *
 * <p>S3 를 아는 코드는 여기까지입니다. 도메인 서비스는 인터페이스만 봅니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3DocumentFileStorage implements DocumentFileStorage {

    private final S3StorageService storage;
    private final FileValidator fileValidator;

    /**
     * 스트림을 <b>여기서 열고 여기서 닫습니다.</b>
     *
     * <p>AWS SDK v2 의 {@code RequestBody.fromInputStream} 은 다 읽은 뒤에도 스트림을
     * 닫아주지 않습니다. 여는 쪽과 닫는 쪽이 갈리면 어느 한 경로에서 반드시 새므로,
     * 소비하는 이 자리에서 try-with-resources 로 묶습니다.
     */
    @Override
    public String store(String userId, UUID documentPublicId, UploadedFile file) {
        String extension = fileValidator.extensionOf(file.fileName());
        String objectKey = ObjectKeys.resume(userId, documentPublicId.toString(), extension);

        try (InputStream content = file.content().getInputStream()) {
            storage.put(objectKey, content, file.size(), file.contentType());
        } catch (IOException e) {
            log.warn("업로드된 파일을 읽지 못했습니다 key={}", objectKey, e);
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "업로드된 파일을 읽지 못했습니다");
        }
        return objectKey;
    }

    /**
     * 보상 삭제. 원래 실패 원인을 덮지 않도록 예외를 밖으로 내보내지 않습니다.
     *
     * <p>삭제까지 실패하면 버킷에 고아 객체가 남습니다. 지금은 로그만 남기고
     * 넘어갑니다. 쌓이는 양이 문제가 되면 정리 배치를 붙이세요.
     */
    @Override
    public void remove(String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException e) {
            log.error("업로드 보상 삭제 실패. 고아 객체가 남습니다 key={}", objectKey, e);
        }
    }
}
