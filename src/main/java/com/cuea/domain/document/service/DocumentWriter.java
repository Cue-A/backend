package com.cuea.domain.document.service;

import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 쓰기만 담당합니다.
 *
 * <p>{@code DocumentRegisterService} 에서 분리한 이유는 <b>S3 업로드를 트랜잭션
 * 밖에 두기 위해서</b>입니다. 업로드를 트랜잭션 안에 넣으면 네트워크가 느린 만큼
 * DB 커넥션을 붙들고 있게 됩니다. {@code docs/01-conventions.md} 참고.
 */
@Component
@RequiredArgsConstructor
public class DocumentWriter {

    private final DocumentRepository documentRepository;

    @Transactional
    public Document save(Document document) {
        return documentRepository.save(document);
    }
}
