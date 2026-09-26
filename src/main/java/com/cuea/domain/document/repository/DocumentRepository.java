package com.cuea.domain.document.repository;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
 *
 * <h2>삭제된 문서는 어떤 메서드로도 나오지 않습니다</h2>
 * 소프트 삭제({@code deleted_at})라 행이 남아 있습니다(Issue #38). 조건을 호출하는
 * 쪽에 맡기면 한 곳만 빠뜨려도 지운 문서가 목록에 뜨거나 면접에 쓰입니다. 그래서
 * <b>모든 조회에 {@code deletedAt is null} 을 박아두고</b>, 삭제된 문서를 꺼내는
 * 메서드는 두지 않습니다. 소유자 조건을 쿼리에 묶는 것과 같은 이유입니다.
 */
public interface DocumentRepository extends Repository<Document, Long> {

    /**
     * 조회는 항상 소유자와 함께 겁니다.
     *
     * <p>{@code findByPublicId} 로 찾고 서비스에서 소유자를 비교하는 방식은, 비교를
     * 한 번 빠뜨리면 남의 문서가 그대로 열립니다. 쿼리에 묶어두면 잊을 수가 없습니다.
     */
    @Query("select d from Document d"
            + " where d.publicId = :publicId and d.user.userId = :userId and d.deletedAt is null")
    Optional<Document> findByPublicIdAndUser_UserId(@Param("publicId") UUID publicId,
                                                    @Param("userId") String userId);

    /** 등록 상한 계산. 삭제한 문서를 세면 지워도 자리가 나지 않습니다. */
    @Query("select count(d) from Document d where d.user.userId = :userId and d.deletedAt is null")
    long countByUser_UserId(@Param("userId") String userId);

    Document save(Document document);

    /**
     * 목록 조회도 소유자로 먼저 좁힙니다.
     *
     * <p>{@code findAll(pageable)} 로 가져와 걸러내는 방식은, 남의 문서가 페이지를
     * 차지해 내 문서가 빈 페이지로 보입니다. 개수와 페이지 수도 전부 틀립니다.
     */
    @Query("select d from Document d where d.user.userId = :userId and d.deletedAt is null")
    Page<Document> findByUser_UserId(@Param("userId") String userId, Pageable pageable);

    @Query("select d from Document d"
            + " where d.user.userId = :userId and d.docType = :docType and d.deletedAt is null")
    Page<Document> findByUser_UserIdAndDocType(@Param("userId") String userId,
                                              @Param("docType") DocType docType,
                                              Pageable pageable);
}
