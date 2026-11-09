package com.cuea.domain.report.repository;

import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.entity.ReportRetryStatus;
import com.cuea.domain.report.entity.ReportStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
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

    /**
     * 공유 토큰으로 조회. 공개 리포트 조회 전용이라 소유자 조건이 없습니다. <b>본인 API 에서 쓰지 마세요.</b>
     * 토큰은 공유한 리포트에만 있으므로, 공유하지 않았거나 해제한 리포트는 나오지 않습니다.
     */
    @Query("select r from Report r where r.shareId = :shareId")
    Optional<Report> findByShareIdForPublic(@Param("shareId") UUID shareId);

    Report save(Report report);

    /**
     * 공유하지 않은 리포트에 공유 토큰을 씁니다.
     *
     * <p>{@link #reopenFailed} 와 같은 이유로 조건부 UPDATE 한 번으로 합니다. 공유를 두 번 눌러도
     * {@code share_id is null} 을 만족하는 쪽은 하나뿐이라 링크가 하나만 생깁니다.
     *
     * @return 바뀐 행 수. 0 이면 이미 공유 중입니다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Report r
               set r.shareId = :shareId,
                   r.sharedAt = :sharedAt
             where r.reportId = :reportId
               and r.shareId is null
            """)
    int share(@Param("reportId") Long reportId,
              @Param("shareId") UUID shareId,
              @Param("sharedAt") OffsetDateTime sharedAt);

    /** 본인 세션들의 리포트 요약. 연습 기록 목록용입니다. */
    @Query("""
            select new com.cuea.domain.report.repository.ReportSummaryRow(
                   r.session.sessionId, r.publicId, r.status, r.scoreTotal)
              from Report r
             where r.session.user.userId = :userId
               and r.session.sessionId in :sessionIds
            """)
    List<ReportSummaryRow> findSummaries(@Param("userId") String userId,
                                         @Param("sessionIds") Collection<String> sessionIds);

    /**
     * 홈 "최근 리포트 요약". 본인 세션의 리포트 중 상태가 맞는 것만 연습을 최근에 본 순서로 꺼냅니다.
     *
     * <p><b>리포트를 먼저 거르고 자릅니다.</b> 세션을 먼저 N건 자른 뒤 리포트를 붙이면,
     * 최신 세션들에 리포트가 없을 때 결과가 비어버립니다(Issue #107). 그래서 리포트에서
     * 출발해 소유자·상태로 좁힌 뒤 정렬하고, {@code Pageable} 로 그 결과의 앞 N건만 가져옵니다.
     *
     * <p><b>정렬·표시 기준은 세션의 완료 시각({@code session.completed_at})입니다.</b>
     * {@code report.completed_at} 은 재분석(FAILED 재요청·PARTIAL 축 재시도)이 끝날 때마다 다시
     * 찍혀서, 2주 전 연습을 오늘 재분석하면 맨 위로 올라오고 날짜도 오늘로 보입니다. "최근 리포트" 는
     * 연습을 본 시점이 기준이라, 재분석에도 바뀌지 않는 세션 완료 시각을 씁니다(PR #108 리뷰).
     *
     * <p>회사는 미선택 연습이면 null 이라 {@code left join} 입니다. {@code report_data}(jsonb)
     * 는 읽지 않도록 필요한 칸만 생성자로 투영합니다. 완료 시각이 같으면 {@code publicId} 로
     * 순서를 고정해 페이지가 흔들리지 않게 합니다.
     */
    @Query("""
            select new com.cuea.domain.report.repository.RecentReportRow(
                   r.publicId, c.companyName, s.jobRole, s.questionCount,
                   r.status, r.scoreTotal, s.completedAt)
              from Report r
              join r.session s
              left join s.company c
             where s.user.userId = :userId
               and r.status in :statuses
             order by s.completedAt desc, r.publicId desc
            """)
    List<RecentReportRow> findRecentViewable(@Param("userId") String userId,
                                             @Param("statuses") Collection<ReportStatus> statuses,
                                             Pageable pageable);

    /** 본인 세션들의 리포트 중 상태가 맞는 것. 회차 비교용이라 원본까지 읽고, 세션 ID 를 쓰므로 세션을 함께 가져옵니다. */
    @Query("""
            select r from Report r
              join fetch r.session s
             where s.user.userId = :userId
               and s.sessionId in :sessionIds
               and r.status in :statuses
            """)
    List<Report> findAllBySessionIds(@Param("userId") String userId,
                                     @Param("sessionIds") Collection<String> sessionIds,
                                     @Param("statuses") Collection<ReportStatus> statuses);

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

    /**
     * 홈 "기간 평균 · 최고" 점수(Issue #114). 소유자의 리포트 중 열람 가능하고({@code COMPLETED}·
     * {@code PARTIAL}) 점수가 있는({@code score_total IS NOT NULL}) 것만 평균·최고를 냅니다.
     *
     * <p>{@code report} 에 {@code user_id} 가 없어 {@code session} 을 거쳐 소유자를 좁힙니다.
     * 기간은 <b>세션(연습) 완료 시각</b>({@code session.completed_at}) 기준입니다. 재분석(FAILED
     * 재요청·PARTIAL 축 재시도)이 끝날 때마다 {@code report.completed_at} 은 다시 찍혀, 9월 연습을
     * 11월에 재분석하면 점수가 11월로 밀립니다. 잔디(활동)와 점수의 날짜 기준을 세션 완료로 맞춰, 같은
     * 연습이 두 그래프에서 같은 시점에 놓이게 합니다(PR #116 리뷰). 반열림 구간 {@code [start, end)}
     * 를 KST 기간의 UTC 경계로 받습니다. 대상이 없으면 {@code avg}·{@code max} 가 NULL 이라
     * {@link ScoreSummaryRow} 의 두 값이 모두 null 입니다(0 아님).
     *
     * <p>{@code report_data}(jsonb)는 읽지 않습니다 — 집계 함수만 돌립니다.
     */
    @Query(value = """
            select avg(r.score_total) as average, max(r.score_total) as best
              from report r
              join session s on s.session_id = r.session_id
             where s.user_id = :userId
               and r.status in ('COMPLETED', 'PARTIAL')
               and r.score_total is not null
               and s.completed_at >= :startInclusive
               and s.completed_at < :endExclusive
            """, nativeQuery = true)
    ScoreSummaryRow summarizeScores(@Param("userId") String userId,
                                    @Param("startInclusive") OffsetDateTime startInclusive,
                                    @Param("endExclusive") OffsetDateTime endExclusive);

    /**
     * 점수 추이(회차별, 4w·10w). 기간 안에서 점수가 있는 리포트를 <b>세션 완료 최신순</b>으로 돌려줍니다.
     * 서비스가 앞에서 N개(보통 8)를 가져와 오래된 순으로 뒤집습니다.
     *
     * <p>정렬·기간·날짜 라벨 모두 <b>세션 완료 시각</b>({@code session.completed_at})을 씁니다
     * (재분석에도 흔들리지 않게 — PR #116 리뷰). 같은 날 여러 리포트도 각각 한 점이고, 완료 시각이
     * 같을 수 있어 {@code public_id} 로 2차 정렬해 페이지가 흔들리지 않게 합니다. 소유권은
     * {@code session} 조인으로 제한합니다.
     */
    @Query(value = """
            select s.completed_at as completedAt, r.score_total as score
              from report r
              join session s on s.session_id = r.session_id
             where s.user_id = :userId
               and r.status in ('COMPLETED', 'PARTIAL')
               and r.score_total is not null
               and s.completed_at >= :startInclusive
               and s.completed_at < :endExclusive
             order by s.completed_at desc, r.public_id desc
            """, nativeQuery = true)
    List<ScorePointRow> findScorePointsLatestFirst(@Param("userId") String userId,
                                                    @Param("startInclusive") OffsetDateTime startInclusive,
                                                    @Param("endExclusive") OffsetDateTime endExclusive,
                                                    Pageable pageable);

    /**
     * 점수 추이(월 평균, 6m·1y). KST 기준 월별로 점수 있는 리포트의 평균을 <b>오래된 달부터</b>
     * 돌려줍니다. 유효 점수가 없는 달은 행이 없어 0 으로 끌어내리지 않습니다.
     *
     * <p>월 묶음·라벨("YYYY-MM")·기간 경계 모두 <b>세션 완료 시각</b>({@code session.completed_at})을
     * {@code AT TIME ZONE 'Asia/Seoul'} 로 옮겨 만듭니다(재분석에도 달이 밀리지 않게 — PR #116 리뷰).
     */
    @Query(value = """
            select to_char((s.completed_at at time zone 'Asia/Seoul'), 'YYYY-MM') as yearMonth,
                   avg(r.score_total) as average
              from report r
              join session s on s.session_id = r.session_id
             where s.user_id = :userId
               and r.status in ('COMPLETED', 'PARTIAL')
               and r.score_total is not null
               and s.completed_at >= :startInclusive
               and s.completed_at < :endExclusive
             group by yearMonth
             order by yearMonth
            """, nativeQuery = true)
    List<MonthlyScoreRow> findMonthlyScoreAverages(@Param("userId") String userId,
                                                    @Param("startInclusive") OffsetDateTime startInclusive,
                                                    @Param("endExclusive") OffsetDateTime endExclusive);

    /**
     * RISING(우상향) 배지 판정용. 본인의 <b>유효 점수</b> 전체를 연습(세션) 완료 순서로
     * 오래된 것부터 돌려줍니다(Issue #131).
     *
     * <p>유효 점수 정책은 홈 점수 통계({@link #findScorePointsLatestFirst}·{@link #summarizeScores})와
     * 동일합니다: {@code status in (COMPLETED, PARTIAL)} 이고 {@code score_total is not null}.
     * 정렬 기준도 같게 <b>세션 완료 시각</b>({@code session.completed_at}) 오름차순, 완료 시각이
     * 같으면 {@code report.public_id} 로 안정 정렬합니다(재분석에도 순서가 흔들리지 않게 —
     * PR #116 리뷰). 재연습 리포트도 각각 한 점으로 포함됩니다.
     *
     * <p>기간 제한이 없습니다. "과거 어느 시점이든 3회 연속 상승했으면 획득" 을 판정하려면
     * 전체 수열이 필요합니다. 사용자당 보통 수십 개라 전체를 올려 서비스에서 연속 증가를 셉니다.
     * {@code report_data}(jsonb)는 읽지 않고 점수 한 칸만 투영합니다.
     */
    @Query(value = """
            select r.score_total
              from report r
              join session s on s.session_id = r.session_id
             where s.user_id = :userId
               and r.status in ('COMPLETED', 'PARTIAL')
               and r.score_total is not null
             order by s.completed_at asc, r.public_id asc
            """, nativeQuery = true)
    List<Integer> findValidScoresOldestFirst(@Param("userId") String userId);
}
