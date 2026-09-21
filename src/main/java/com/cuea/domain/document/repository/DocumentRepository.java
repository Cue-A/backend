package com.cuea.domain.document.repository;

import com.cuea.domain.document.entity.Document;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 소유자가 있는 엔티티라 {@code findById} 가 없습니다.
 * docs/01-conventions.md 의 "소유자 있는 엔티티는 Repository 를 상속합니다" 참고.
 *
 * <p>내부 PK({@code docId})가 아니라 외부 노출용 {@code publicId} 로 조회합니다.
 */
public interface DocumentRepository extends Repository<Document, Long> {

    Optional<Document> findByPublicIdAndUser_UserId(UUID publicId, String userId);

    Document save(Document document);
}
