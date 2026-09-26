package com.cuea.domain.report.repository;

import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 소유자가 있는 엔티티라 {@code JpaRepository} 를 상속하지 않습니다.
 * docs/01-conventions.md 의 "소유자 있는 엔티티는 Repository 를 상속합니다" 참고.
 *
 * <p>리포트의 소유자는 세션의 소유자입니다. 등록 API 는 세션 소유권을 먼저 확인한 뒤
 * 이 리포지토리를 부릅니다.
 */
public interface ReportRepository extends Repository<Report, Long> {

    /** 세션의 리포트. <b>세션 소유권을 확인한 뒤에만</b> 부르세요. 소유자 조건이 없습니다. */
    Optional<Report> findBySession_SessionId(String sessionId);

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
}
