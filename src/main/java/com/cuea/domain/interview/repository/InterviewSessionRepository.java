package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 소유자가 있는 엔티티라 {@code findById} 가 없습니다.
 * docs/01-conventions.md 의 "소유자 있는 엔티티는 Repository 를 상속합니다" 참고.
 */
public interface InterviewSessionRepository extends Repository<InterviewSession, String> {

    Optional<InterviewSession> findBySessionIdAndUser_UserId(String sessionId, String userId);

    /**
     * 소유자 검사 없이 세션 ID 만으로 조회합니다. <b>API 경계에서 쓰지 마세요.</b>
     *
     * <p>이 세션을 만든 시점({@code InterviewSessionWriter.createSession})에 이미
     * {@code userId} 소유권 검사를 거쳤으므로, 같은 요청 흐름 안에서 폴링 실패 시
     * 세션을 {@code ABORTED} 로 정리하는 내부 처리에서만 씁니다.
     */
    @Query("select s from InterviewSession s where s.sessionId = :sessionId")
    Optional<InterviewSession> findBySessionIdForInternal(@Param("sessionId") String sessionId);

    InterviewSession save(InterviewSession session);

    /** 연습 기록 목록과 그 개수 쿼리가 같이 쓰는 조건. 폴더는 조건에만 써서 left join 입니다. */
    String RECORDS_CONDITION = """
              left join s.folder f
             where s.user.userId = :userId
               and s.status in :statuses
               and (:folderId is null or f.publicId = :folderId)
               and (lower(c.companyName) like :pattern escape '!' or lower(s.jobRole) like :pattern escape '!')
            """;

    /**
     * 연습 기록 목록. 회사는 이름을 보여주므로 함께 가져옵니다.
     *
     * @param folderId null 이면 폴더를 보지 않습니다
     * @param pattern  회사명 · 직무에 거는 소문자 LIKE 패턴. 검색어가 없으면 {@code %}
     */
    @Query(value = "select s from InterviewSession s left join fetch s.company c" + RECORDS_CONDITION,
            countQuery = "select count(s) from InterviewSession s left join s.company c" + RECORDS_CONDITION)
    Page<InterviewSession> findRecords(@Param("userId") String userId,
                                       @Param("statuses") Collection<SessionStatus> statuses,
                                       @Param("folderId") UUID folderId,
                                       @Param("pattern") String pattern,
                                       Pageable pageable);

    /** 루트 세션들의 재연습 세션. 회차 계산용이라 중단된 것은 뺍니다. */
    @Query("""
            select s from InterviewSession s
             where s.user.userId = :userId
               and s.retryOfSession.sessionId in :rootIds
               and s.status <> :aborted
            """)
    List<InterviewSession> findRetriesOf(@Param("userId") String userId,
                                         @Param("rootIds") Collection<String> rootIds,
                                         @Param("aborted") SessionStatus aborted);
}
