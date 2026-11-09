package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.CommonQuestionNote;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 조회는 항상 소유자와 함께 겁니다. */
public interface CommonQuestionNoteRepository extends Repository<CommonQuestionNote, Long> {

    /**
     * 노트 행이 없으면 빈 행(즐겨찾기 꺼짐 · 메모 없음)을 만듭니다. 처음 즐겨찾기 · 메모하는 요청이 동시에 두 번 와도
     * UNIQUE 위반(500) 없이 한 행만 남습니다. JPA {@code save} 후 예외를 잡으면 트랜잭션이 rollback-only 가 돼
     * 커밋 때 500 이 나서 질문은행 북마크와 같은 방식을 씁니다.
     */
    @Modifying
    @Query(value = """
            insert into common_question_note (user_id, common_question_id, favorite, created_at, updated_at)
            values (:userId, :commonQuestionId, false, now(), now())
            on conflict (user_id, common_question_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId, @Param("commonQuestionId") Long commonQuestionId);

    @Query("""
            select n from CommonQuestionNote n
             where n.user.userId = :userId
               and n.commonQuestion.commonQuestionId = :commonQuestionId
            """)
    Optional<CommonQuestionNote> findOwned(@Param("userId") String userId,
                                           @Param("commonQuestionId") Long commonQuestionId);

    @Query("select n from CommonQuestionNote n where n.user.userId = :userId")
    List<CommonQuestionNote> findAllOwned(@Param("userId") String userId);
}
