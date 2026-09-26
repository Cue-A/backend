package com.cuea.domain.report.entity;

import com.cuea.common.entity.BaseCreatedEntity;
import com.cuea.domain.interview.entity.InterviewSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 세션 하나의 결과 리포트. {@link InterviewSession} 과 1:1 입니다.
 *
 * <p>외부로 나가는 식별자는 {@code publicId}(응답의 {@code reportId})입니다.
 * {@code reportId} 는 연속된 정수라 노출하지 않습니다.
 *
 * <h2>세션당 한 행</h2>
 * {@code session_id} 가 UNIQUE 입니다. 실패({@code FAILED})한 리포트를 다시 요청하면
 * 새 행을 만들지 않고 이 행을 {@code PROCESSING} 으로 되돌리며 {@code attempt} 를
 * 올립니다. AI 의 Idempotency-Key 가 {@code rpt_{sessionId}_{attempt}} 라서, 시도
 * 번호가 같으면 AI 가 실패한 기존 task 를 그대로 돌려주기 때문입니다.
 *
 * <h2>점수가 전부 nullable 인 이유</h2>
 * 말하기·시선 축은 실패해도 리포트가 만들어지고({@code PARTIAL}) 그 축만 빕니다.
 * 카메라를 안 썼으면 시선은 {@code skipped} 라 역시 null 입니다. 내용 축이 실패하면
 * 리포트 자체가 {@code FAILED} 입니다. docs/13-report.md 참고.
 */
@Entity
@Table(name = "report")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private InterviewSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    /** 지금 폴링 중인(또는 마지막으로 폴링한) AI task. */
    @Column(name = "ai_task_id", length = 100)
    private String aiTaskId;

    /** 시도 번호. 1부터. Idempotency-Key {@code rpt_{sessionId}_{attempt}} 에 들어갑니다. */
    @Column(nullable = false)
    private int attempt;

    /** 총점 0~100. AI {@code overall.score}. 게이트가 적용된 최종값입니다. */
    @Column(name = "score_total")
    private Integer scoreTotal;

    /** 내용 축 0~100. */
    @Column(name = "score_content")
    private Integer scoreContent;

    /** 말하기 축 0~100. 실패 시 null. */
    @Column(name = "score_speech")
    private Integer scoreSpeech;

    /** 시선 축 0~100. 실패하거나 카메라 미사용({@code skipped})이면 null. */
    @Column(name = "score_gaze")
    private Integer scoreGaze;

    /**
     * AI {@code result} 원본 전체.
     *
     * <p><b>지우지 마세요.</b> 회차 비교({@code /ai/reports/compare})는 AI 가 리포트를
     * 보관하지 않아 전체 회차의 원본을 Backend 가 다시 보냅니다. 조회 조건으로도 쓰지
     * 마세요. 필요한 값은 컬럼으로 꺼내 둡니다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_data", columnDefinition = "jsonb")
    private Map<String, Object> reportData;

    /** {@code FAILED} 일 때 원인. AI error_code 또는 Backend 코드({@code AI_TIMEOUT} 등). */
    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** 첫 요청. AI 에 task 를 이미 등록한 뒤에 만듭니다. */
    public static Report processing(InterviewSession session, String aiTaskId) {
        return Report.builder()
                .publicId(UUID.randomUUID())
                .session(session)
                .status(ReportStatus.PROCESSING)
                .aiTaskId(aiTaskId)
                .attempt(1)
                .build();
    }

    /** 자동 재시도로 새 task 를 받았습니다. */
    public void retryWith(int attempt, String aiTaskId) {
        this.attempt = attempt;
        this.aiTaskId = aiTaskId;
    }

    /** {@code COMPLETED} 또는 {@code PARTIAL}. */
    public void finish(ReportStatus status,
                       Integer scoreTotal,
                       Integer scoreContent,
                       Integer scoreSpeech,
                       Integer scoreGaze,
                       Map<String, Object> reportData) {
        this.status = status;
        this.scoreTotal = scoreTotal;
        this.scoreContent = scoreContent;
        this.scoreSpeech = scoreSpeech;
        this.scoreGaze = scoreGaze;
        this.reportData = reportData;
        this.errorCode = null;
        this.completedAt = OffsetDateTime.now();
    }

    public void fail(String errorCode) {
        this.status = ReportStatus.FAILED;
        this.errorCode = errorCode;
        this.completedAt = OffsetDateTime.now();
    }
}
