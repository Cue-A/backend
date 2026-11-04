package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.BookmarkedQuestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 질문은행. 소유자가 있는 엔티티라 {@code findById} 가 없고, 모든 조회 · 삭제에 {@code userId} 를 묶습니다.
 * docs/01-conventions.md 의 "소유자 있는 엔티티는 Repository 를 상속합니다" 참고.
 *
 * <p>행을 넣고 지울 때 {@code question.is_bookmarked} 도 같이 바꿔야 합니다. 그건 서비스 몫입니다.
 */
public interface BookmarkedQuestionRepository extends Repository<BookmarkedQuestion, Long> {

    /**
     * 담기. <b>이미 있으면 아무 일도 하지 않습니다(멱등).</b>
     *
     * <p>JPA {@code save} 로 넣고 UNIQUE 예외를 삼키면 트랜잭션이 rollback-only 가 돼 커밋 때 500 이
     * 납니다. {@code ON CONFLICT DO NOTHING} 은 위반 자체를 일으키지 않습니다
     * ({@code UserCompanyInterestRepository} 와 같은 이유).
     *
     * <p>네이티브 INSERT 라 {@code @PrePersist} 가 돌지 않아 시각을 {@code now()} 로 직접 채웁니다.
     *
     * @return 넣은 행 수. 1 이면 새로 담았고 0 이면 이미 있었습니다
     */
    @Modifying
    @Query(value = """
            insert into bookmarked_question (user_id, session_id, question_id, created_at, updated_at)
            values (:userId, :sessionId, :questionId, now(), now())
            on conflict (user_id, session_id, question_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId,
                       @Param("sessionId") String sessionId,
                       @Param("questionId") String questionId);

    /** 내 북마크 한 건을 가리키는 조건. 찾기와 지우기가 같이 씁니다. */
    String OWNED_CONDITION = """
             where b.user.userId = :userId
               and b.question.sessionId = :sessionId
               and b.question.questionId = :questionId
            """;

    @Query("select b from BookmarkedQuestion b" + OWNED_CONDITION)
    Optional<BookmarkedQuestion> findOwned(@Param("userId") String userId,
                                           @Param("sessionId") String sessionId,
                                           @Param("questionId") String questionId);

    /**
     * 질문은행 목록. 담은 시각 최신순이고 같은 시각이면 나중에 담은 것이 먼저입니다.
     *
     * <p>응답에 질문 문구 · 직무 · 회사명이 들어가 질문과 세션을 함께 가져옵니다. 회사는 고르지 않은
     * 세션이 있어 left join 입니다. inner join 이면 그 북마크가 목록에서 빠집니다.
     * 정렬이 쿼리에 있으므로 {@code pageable} 에는 정렬을 넣지 마세요.
     */
    @Query(value = """
            select b from BookmarkedQuestion b
              join fetch b.question q
              join fetch q.session s
              left join fetch s.company
             where b.user.userId = :userId
             order by b.createdAt desc, b.bookmarkedQuestionId desc
            """,
            countQuery = "select count(b) from BookmarkedQuestion b where b.user.userId = :userId")
    Page<BookmarkedQuestion> findPageOwned(@Param("userId") String userId, Pageable pageable);

    /** 내 북마크를 지웁니다. 메모도 같은 행이라 함께 사라집니다. @return 지운 행 수. 없던 것이면 0 */
    @Modifying
    @Query("delete from BookmarkedQuestion b" + OWNED_CONDITION)
    int deleteOwned(@Param("userId") String userId,
                    @Param("sessionId") String sessionId,
                    @Param("questionId") String questionId);
}
