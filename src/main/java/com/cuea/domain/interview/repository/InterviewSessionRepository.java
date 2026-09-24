package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.InterviewSession;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

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

    /**
     * 이 문서로 본 면접이 하나라도 있는지. 세션 내용을 돌려주지 않아 소유자 조건이
     * 없습니다. 호출하는 쪽이 이미 소유권을 확인한 문서의 {@code docId} 만 넘깁니다.
     */
    boolean existsByDocument_DocId(Long docId);

    InterviewSession save(InterviewSession session);
}
