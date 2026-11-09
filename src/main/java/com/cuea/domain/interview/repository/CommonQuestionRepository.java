package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.CommonQuestion;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 내 질문은 소유자가 있어 {@code findById} 를 열지 않습니다. 한 개를 찾을 때는 항상 {@link #findVisible} 로
 * 기본 질문과 내 질문만 봅니다. docs/01-conventions.md 의 "소유자 있는 엔티티는 Repository 를 상속합니다" 참고.
 */
public interface CommonQuestionRepository extends Repository<CommonQuestion, Long> {

    /** 기본 질문. 시드에 넣은 순서(PK 순)입니다. */
    @Query("select q from CommonQuestion q where q.user is null order by q.commonQuestionId")
    List<CommonQuestion> findAllDefaults();

    /** 내 질문. 최근에 추가한 것부터, 같은 시각이면 PK 역순으로 고정합니다. */
    List<CommonQuestion> findAllByUser_UserIdOrderByCreatedAtDescCommonQuestionIdDesc(String userId);

    /** 기본 질문이거나 내 질문일 때만 찾습니다. 남의 내 질문은 없는 것과 같습니다. */
    @Query("""
            select q from CommonQuestion q
              left join q.user u
             where q.publicId = :publicId
               and (u is null or u.userId = :userId)
            """)
    Optional<CommonQuestion> findVisible(@Param("publicId") UUID publicId, @Param("userId") String userId);

    long countByUser_UserId(String userId);

    CommonQuestion save(CommonQuestion question);

    /** 즐겨찾기 · 메모는 DB 의 {@code ON DELETE CASCADE} 가 지웁니다. */
    void delete(CommonQuestion question);
}
