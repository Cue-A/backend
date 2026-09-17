package com.cuea.domain.document.repository;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 소유자가 있는 엔티티라 {@code JpaRepository} 를 상속하지 않습니다.
 *
 * <p>상속하면 {@code findById}·{@code findAll}·{@code deleteById} 가 같이 딸려옵니다.
 * 소유자 스코프 쿼리를 만들어 둬도 옆에 있는 {@code findById} 를 그냥 쓰면 그대로
 * 뚫립니다. 필요한 메서드만 열어두면 쓰려고 해도 컴파일이 안 됩니다.
 * {@code docs/01-conventions.md} 의 "소유자 있는 엔티티는 Repository 를 상속합니다" 참고.
 *
 * <p>내부 PK({@code docId})가 아니라 외부 노출용 {@code publicId} 로 조회합니다.
 */
public interface DocumentRepository extends Repository<Document, Long> {

    /**
     * 조회는 항상 소유자와 함께 겁니다.
     *
     * <p>{@code findByPublicId} 로 찾고 서비스에서 소유자를 비교하는 방식은, 비교를
     * 한 번 빠뜨리면 남의 문서가 그대로 열립니다. 쿼리에 묶어두면 잊을 수가 없습니다.
     */
    Optional<Document> findByPublicIdAndUser_UserId(UUID publicId, String userId);

    long countByUser_UserId(String userId);

    Document save(Document document);

    /**
     * 목록 조회도 소유자로 먼저 좁힙니다.
     *
     * <p>{@code findAll(pageable)} 로 가져와 걸러내는 방식은, 남의 문서가 페이지를
     * 차지해 내 문서가 빈 페이지로 보입니다. 개수와 페이지 수도 전부 틀립니다.
     */
    Page<Document> findByUser_UserId(String userId, Pageable pageable);

    Page<Document> findByUser_UserIdAndDocType(String userId, DocType docType, Pageable pageable);
}
