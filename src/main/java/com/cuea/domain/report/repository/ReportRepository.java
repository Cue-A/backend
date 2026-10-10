package com.cuea.domain.report.repository;

import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportRetryStatus;
import com.cuea.domain.report.entity.ReportStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 소유자가 있는 엔티티라 {@code JpaRepository} 를 상속하지 않습니다.
 * docs/01-conventions.md 의 "소유자 있는 엔티티는 Repository 를 상속합니다" 참고.
 *
 * <p>리포트의 소유자는 세션의 소유자입니다. {@code report} 에 {@code user_id} 가 없어
 * 세션을 거쳐 소유자로 좁힙니다.
 */
public interface ReportRepository extends Repository<Report, Long> {

    /** 본인 세션의 리포트. 소유자 조건을 쿼리에 묶어 남의 리포트는 애초에 나오지 않습니다. */
    Optional<Report> findBySession_SessionIdAndSession_User_UserId(String sessionId, String userId);

    /** 본인 리포트를 외부 식별자({@code reportId})로. 남의 리포트는 없는 것과 같습니다. */
    Optional<Report> findByPublicIdAndSession_User_UserId(UUID publicId, String userId);

    /**
     * 내부 PK 로 조회. 백그라운드 폴러 전용입니다. <b>API 경계에서 쓰지 마세요.</b>
     * 등록 요청 때 소유권을 확인한 리포트만 폴러로 넘어옵니다.
     */
    @Query("select r from Report r where r.reportId = :reportId")
    Optional<Report> findByIdForInternal(@Param("reportId") Long reportId);

    Report save(Report report);

    /**
     * 실패한 리포트를 다시 {@code PROCESSING} 으로 되돌립니다.
     *
     * <p>조회해서 고치지 않고 조건부 UPDATE 한 번으로 합니다. 같은 사용자가 재요청을
     * 두 번 눌러도 {@code status = FAILED and attempt = :previousAttempt} 를 만족하는
     * 쪽은 하나뿐이라, 나머지는 0행이 되어 409 로 돌려보낼 수 있습니다.
     *
     * @return 바뀐 행 수. 0 이면 다른 요청이 먼저 가져갔습니다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Report r
               set r.status = :processing,
                   r.attempt = :nextAttempt,
                   r.aiTaskId = :aiTaskId,
                   r.errorCode = null,
                   r.completedAt = null
             where r.reportId = :reportId
               and r.status = :failed
               and r.attempt = :previousAttempt
            """)
    int reopenFailed(@Param("reportId") Long reportId,
                     @Param("previousAttempt") int previousAttempt,
                     @Param("nextAttempt") int nextAttempt,
                     @Param("aiTaskId") String aiTaskId,
                     @Param("processing") ReportStatus processing,
                     @Param("failed") ReportStatus failed);

    /**
     * PARTIAL 리포트의 실패 축 재시도를 시작합니다. {@code status} 는 PARTIAL 그대로 둡니다.
     *
     * <p>{@link #reopenFailed} 와 같은 이유로 조건부 UPDATE 한 번으로 합니다. 재시도를 두 번
     * 눌러도 {@code attempt} 를 먼저 올린 쪽만 통과합니다. 결과({@code report_data} · 점수)는
     * 재시도가 실패하면 그대로 보여줘야 하므로 건드리지 않습니다.
     *
     * @return 바뀐 행 수. 0 이면 다른 요청이 먼저 시작했습니다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Report r
               set r.retryStatus = :processing,
                   r.attempt = :nextAttempt,
                   r.aiTaskId = :aiTaskId,
                   r.errorCode = null
             where r.reportId = :reportId
               and r.status = :partial
               and r.attempt = :previousAttempt
               and (r.retryStatus is null or r.retryStatus <> :processing)
            """)
    int startRetry(@Param("reportId") Long reportId,
                   @Param("previousAttempt") int previousAttempt,
                   @Param("nextAttempt") int nextAttempt,
                   @Param("aiTaskId") String aiTaskId,
                   @Param("partial") ReportStatus partial,
                   @Param("processing") ReportRetryStatus processing);
}
