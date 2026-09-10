package com.cuea.domain.report.entity;

import com.cuea.common.entity.BaseCreatedEntity;
import com.cuea.domain.interview.entity.InterviewSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 세션 하나의 결과 리포트. {@link InterviewSession} 과 1:1 입니다.
 *
 * <p><b>이 엔티티는 계약 확정 전 상태입니다.</b> docs/90-open-questions.md 는
 * 리포트 계약이 도착하기 전에 만들지 말라고 하고, docs/13-report.md 는 점수
 * 스케일(0~100 인지 1~5 인지)조차 미확정이라고 적혀 있습니다.
 * ERD 초안 기준으로 먼저 잡아둔 것이며 계약 도착 시 갈아엎을 수 있습니다.
 *
 * <p>점수 4개가 전부 nullable 인 것은 <b>부분 실패</b> 때문입니다.
 * 음성·시선 분석이 실패해도 리포트 자체는 생성되고 그 축만 비어 있습니다.
 * 단 내용 분석({@code scoreContent})이 실패하면 총점을 낼 수 없어
 * 전체 실패로 처리합니다. 주제를 벗어난 유창한 답변이 음성·시선 점수만으로
 * 높은 총점을 받는 것을 막는 장치입니다.
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

    /** 내용 축: 논리성·구체성·질문 관련성. 실패 시 총점을 내지 않습니다. */
    @Column(name = "score_content")
    private Integer scoreContent;

    /** 음성 축: 발화 속도·필러워드·침묵. 실패해도 이 축만 빕니다. */
    @Column(name = "score_speech")
    private Integer scoreSpeech;

    /** 시선 축: 화면 응시 비율·이탈 횟수. 실패해도 이 축만 빕니다. */
    @Column(name = "score_vision")
    private Integer scoreVision;

    /** 총점. 내용 분석이 실패하면 null 입니다. */
    @Column(name = "score_total")
    private Integer scoreTotal;

    /** 축별 요약. 키 목록은 계약 도착 후 확정됩니다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> summary;

    /** 회차를 넘나드는 서술형 총평. */
    @Column(name = "growth_narrative", columnDefinition = "text")
    private String growthNarrative;

    /** 세션 안의 시간 흐름. 배열 형태라 {@code List} 로 받습니다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> timeline;

    /** 압박·꼬리질문에 대한 회복력 지표. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> resilience;

    /**
     * 위 컬럼으로 펴지 않은 나머지 원본 전체.
     *
     * <p>계약이 확정되기 전까지 AI 응답을 잃지 않기 위한 보관함입니다.
     * 조회 조건으로 쓰지 마세요. 확정되면 필요한 것만 컬럼으로 승격시킵니다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_data", columnDefinition = "jsonb")
    private Map<String, Object> reportData;

    /** 생성 진행 상태. 값 목록은 계약 도착 후 확정이라 문자열로 둡니다. */
    @Column(nullable = false, length = 20)
    private String status;
}
