package com.cuea.domain.document.service;

import com.cuea.infrastructure.file.DocumentFileStorage;
import com.cuea.infrastructure.file.UploadedFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S3 없이 저장소 경계를 대신합니다.
 *
 * <p>무엇이 올라갔고 무엇이 지워졌는지 기록합니다. 보상 삭제가 실제로 불렸는지를
 * 검증하려면 호출 여부만으로는 부족하고 <b>어떤 키가 지워졌는지</b>를 봐야 합니다.
 */
class FakeDocumentFileStorage implements DocumentFileStorage {

    final List<String> stored = new ArrayList<>();
    final List<String> removed = new ArrayList<>();

    @Override
    public String store(String userId, UUID documentPublicId, UploadedFile file) {
        String objectKey = "resumes/%s/%s".formatted(userId, documentPublicId);
        stored.add(objectKey);
        return objectKey;
    }

    @Override
    public void remove(String objectKey) {
        removed.add(objectKey);
    }
}
