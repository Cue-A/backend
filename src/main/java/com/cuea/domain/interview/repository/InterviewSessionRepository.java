package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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

    /**
     * 재면접 옵션 비교에 필요한 연관({@code document}·{@code company})을 함께 읽어오는 조회입니다.
     *
     * <p><b>요청 사용자 소유(userId)인 root 세션만 조회합니다.</b> source 세션 소유권은 호출부
     * ({@code getOwnedSession})에서 이미 확인하지만, root 세션도 같은 사용자 소유여야 하므로
     * 조회 조건에 {@code userId} 를 포함해 교차 사용자 참조를 막습니다. 추가 쿼리 없이 WHERE
     * 조건 하나로 방어합니다.
     *
     * <p>{@code open-in-view=false} 이고 {@link com.cuea.domain.interview.service.InterviewStartService}
     * 는 트랜잭션 밖에서 root 세션의 옵션을 비교하므로, LAZY 연관을 지연 로딩하면
     * {@code LazyInitializationException} 이 납니다. 비교에 쓰는 연관을 fetch join 으로 미리
     * 초기화해 둡니다.
     */
    @Query("select s from InterviewSession s "
            + "left join fetch s.document "
            + "left join fetch s.company "
            + "where s.sessionId = :sessionId and s.user.userId = :userId")
    Optional<InterviewSession> findRootForReplay(@Param("sessionId") String sessionId,
                                                 @Param("userId") String userId);

    /** 회원 탈퇴 때 세션 미디어 접두사를 모으려고 씁니다. */
    @Query("select s.sessionId from InterviewSession s where s.user.userId = :userId")
    List<String> findSessionIdsByUserId(@Param("userId") String userId);

    InterviewSession save(InterviewSession session);

    /** 연습 기록 목록과 그 개수 쿼리가 같이 쓰는 조건. 회사(c) · 폴더(f)는 없을 수 있어 left join 입니다. */
    String RECORDS_CONDITION = """
             where s.user.userId = :userId
               and s.status in :statuses
               and (:folderId is null or f.publicId = :folderId)
               and (lower(c.companyName) like :pattern escape '!' or lower(s.jobRole) like :pattern escape '!')
            """;

    /**
     * 연습 기록 목록. 회사 이름과 폴더를 보여주므로 함께 가져옵니다.
     *
     * @param folderId null 이면 폴더를 보지 않습니다
     * @param pattern  회사명 · 직무에 거는 소문자 LIKE 패턴. 검색어가 없으면 {@code %}
     */
    @Query(value = "select s from InterviewSession s left join fetch s.company c left join fetch s.folder f"
            + RECORDS_CONDITION,
            countQuery = "select count(s) from InterviewSession s left join s.company c left join s.folder f"
                    + RECORDS_CONDITION)
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

    /** 폴더별 세션 수. 세션이 없는 폴더는 나오지 않습니다. */
    @Query("""
            select new com.cuea.domain.interview.repository.FolderSessionCount(s.folder.folderId, count(s))
              from InterviewSession s
             where s.user.userId = :userId
               and s.folder is not null
               and s.status in :statuses
             group by s.folder.folderId
            """)
    List<FolderSessionCount> countSessionsPerFolder(@Param("userId") String userId,
                                                    @Param("statuses") Collection<SessionStatus> statuses);

    long countByUser_UserIdAndStatusIn(String userId, Collection<SessionStatus> statuses);

    long countByFolder_FolderIdAndStatusIn(Long folderId, Collection<SessionStatus> statuses);

    List<InterviewSession> findAllBySessionIdInAndUser_UserIdAndStatusIn(Collection<String> sessionIds,
                                                                      String userId,
                                                                      Collection<SessionStatus> statuses);

    /**
     * 루트 세션들의 면접 전체(1회차 + 재연습). 시작 순서대로입니다.
     *
     * <p>1회차는 {@code retryOfSession} 이 없어 left join 입니다. inner join 이면 1회차가 빠집니다.
     */
    @Query("""
            select s from InterviewSession s
              left join s.retryOfSession r
             where s.user.userId = :userId
               and s.status in :statuses
               and (s.sessionId in :rootIds or r.sessionId in :rootIds)
             order by s.createdAt, s.sessionId
            """)
    List<InterviewSession> findInterviewsOf(@Param("userId") String userId,
                                            @Param("rootIds") Collection<String> rootIds,
                                            @Param("statuses") Collection<SessionStatus> statuses);

    /**
     * 홈 스트릭·이번 달 연습 일수 계산용. 본인의 <b>완료 세션이 있는 KST 날짜</b>를 중복 없이
     * 오름차순으로 돌려줍니다(Issue #113).
     *
     * <p>엔티티나 {@code report_data} 를 끌어오지 않고 날짜 한 칸만 투영합니다. 연속 일수는
     * 날짜 집합만 있으면 계산되므로, 사용자당 보통 수십~수백 개인 이 목록만 메모리로 올려
     * 서비스에서 연속 구간을 셉니다. 저장된 연속 일수(streak 테이블)는 쓰지 않습니다
     * (writer 가 없고, 조회 시 계산이 더 정확 — {@code Streak} 엔티티 주석 참고).
     *
     * <p>{@code completed_at} 은 {@code COMPLETED} 세션에서만 채워지므로 상태 필터와 날짜
     * 기준이 일치합니다. {@code timestamptz} 를 {@code AT TIME ZONE 'Asia/Seoul'} 로 KST 로
     * 옮긴 뒤 {@code ::date} 로 잘라, UTC 로는 날짜가 달라도 KST 에서 같은 날이면 한 날로 묶입니다.
     */
    @Query(value = """
            select distinct to_char((s.completed_at at time zone 'Asia/Seoul'), 'YYYY-MM-DD') as d
              from session s
             where s.user_id = :userId
               and s.status = 'COMPLETED'
               and s.completed_at is not null
             order by d
            """, nativeQuery = true)
    List<String> findCompletedDatesInKst(@Param("userId") String userId);

    /**
     * 면접 캘린더(Issue #130)의 {@code practicedDates} 용. 반열림 구간 {@code [startInclusive,
     * endExclusive)}(KST 월 경계를 호출부가 UTC {@link OffsetDateTime} 으로 넘김) 안에서 본인의
     * <b>완료 세션이 있는 KST 날짜</b>를 중복 없이 오름차순 {@code YYYY-MM-DD} 로 돌려줍니다.
     *
     * <p>{@link #findCompletedDatesInKst} 와 같은 KST 변환이지만 월 범위만 봅니다. 사용자 등록
     * 일정과 무관하게 <b>완료 세션에서 자동 계산</b>되며, calendar_event 에 중복 INSERT 하지
     * 않습니다(읽기 전용 재사용).
     */
    @Query(value = """
            select distinct to_char((s.completed_at at time zone 'Asia/Seoul'), 'YYYY-MM-DD') as d
              from session s
             where s.user_id = :userId
               and s.status = 'COMPLETED'
               and s.completed_at is not null
               and s.completed_at >= :startInclusive
               and s.completed_at < :endExclusive
             order by d
            """, nativeQuery = true)
    List<String> findCompletedDatesInKstBetween(@Param("userId") String userId,
                                                @Param("startInclusive") OffsetDateTime startInclusive,
                                                @Param("endExclusive") OffsetDateTime endExclusive);

    /**
     * 홈 잔디(활동 히트맵)용. 기간 안에서 <b>KST 날짜별 완료 세션 수</b>를 돌려줍니다(Issue #114).
     * 쉰 날은 행이 없으므로, 빈 날 0 채움은 서비스가 기간 전체 날짜를 돌며 만듭니다.
     *
     * <p>경계는 반열림 구간 {@code [startInclusive, endExclusive)} 입니다. 호출부가 KST 기간의
     * 시작 자정과 (마지막 날 + 1일)의 자정을 UTC {@link OffsetDateTime} 으로 넘겨, 쿼리는
     * {@code completed_at} 을 그대로 비교(인덱스 친화)하고 그룹핑에서만 KST 날짜로 변환합니다.
     */
    @Query(value = """
            select to_char((s.completed_at at time zone 'Asia/Seoul'), 'YYYY-MM-DD') as date,
                   count(*) as count
              from session s
             where s.user_id = :userId
               and s.status = 'COMPLETED'
               and s.completed_at >= :startInclusive
               and s.completed_at < :endExclusive
             group by to_char((s.completed_at at time zone 'Asia/Seoul'), 'YYYY-MM-DD')
             order by to_char((s.completed_at at time zone 'Asia/Seoul'), 'YYYY-MM-DD')
            """, nativeQuery = true)
    List<DailyActivityRow> countDailyActivityInKst(@Param("userId") String userId,
                                                    @Param("startInclusive") OffsetDateTime startInclusive,
                                                    @Param("endExclusive") OffsetDateTime endExclusive);

    /**
     * 기간 내 완료 세션 수(재연습 포함, {@code COMPLETED} 만). 잔디 날짜별 수의 합과 같지만,
     * "총 연습 횟수" 는 날짜 묶음과 무관한 단일 수치라 따로 셉니다. 반열림 구간 {@code [start, end)}.
     */
    @Query(value = """
            select count(*)
              from session s
             where s.user_id = :userId
               and s.status = 'COMPLETED'
               and s.completed_at >= :startInclusive
               and s.completed_at < :endExclusive
            """, nativeQuery = true)
    long countCompletedInPeriod(@Param("userId") String userId,
                                @Param("startInclusive") OffsetDateTime startInclusive,
                                @Param("endExclusive") OffsetDateTime endExclusive);
}
