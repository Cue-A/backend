package com.cuea.domain.document.repository;

import com.cuea.domain.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 조회는 항상 소유자와 함께 겁니다.
     *
     * <p>{@code findByPublicId} 로 찾고 서비스에서 소유자를 비교하는 방식은, 비교를
     * 한 번 빠뜨리면 남의 문서가 그대로 열립니다. 쿼리에 묶어두면 잊을 수가 없습니다.
     * {@code docs/01-conventions.md} 의 소유자 검사 규칙 참고.
     */
    Optional<Document> findByPublicIdAndUser_UserId(UUID publicId, String userId);

    long countByUser_UserId(String userId);
}
