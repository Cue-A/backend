package com.cuea.domain.interview.entity;

import com.cuea.common.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

/**
 * 답변에 대한 AI 분석 결과. {@link Question} 과 1:1 입니다.
 *
 * <p>질문을 받자마자 빈 행을 만들지 않고 <b>분석 결과가 들어온 뒤에</b> 만듭니다.
 * 답변하지 않고 끝난 질문에는 이 행이 없습니다. 문항 수를 셀 때 {@code question}
 * 기준인지 {@code answer} 기준인지 구분하세요.
 *
 * <p>녹음 파일 위치는 여기가 아니라 {@link Question#getAnswerAudioUrl()} 입니다.
 * 업로드 완료 시점에는 아직 분석 전이라 이 행이 없기 때문입니다.
 *
 * <p>{@link Question} 이 복합키라 FK 도 {@code (session_id, question_id)} 두
 * 컬럼입니다.
 */
@Entity
@Table(
        name = "answer",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_answer_question",
                columnNames = {"session_id", "question_id"}
        )
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Answer extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_id")
    private Long answerId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "session_id", referencedColumnName = "session_id", nullable = false),
            @JoinColumn(name = "question_id", referencedColumnName = "question_id", nullable = false)
    })
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Question question;

    /** STT 결과 전문. 받아쓰기가 실패했으면 null. */
    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    /** 이 답변의 내용 점수. 리포트 계약 도착 전이라 스케일은 미확정입니다. */
    @Column
    private Integer score;

    /** 녹음 파일 안에서 발화가 시작된 지점(초). */
    @Column(name = "answer_start")
    private Double answerStart;

    /** 녹음 파일 안에서 발화가 끝난 지점(초). */
    @Column(name = "answer_end")
    private Double answerEnd;

    /** 질문을 들은 뒤 말을 시작하기까지 걸린 시간(초). */
    @Column(name = "think_time")
    private Double thinkTime;

    @Column(name = "answered_at")
    private OffsetDateTime answeredAt;

    /**
     * 음성·시선 분석 원본. 발화 속도, 필러워드, 침묵, 화면 응시 비율 등.
     *
     * <p>축이 늘거나 필드가 바뀌어도 스키마를 안 건드리려고 jsonb 로 둡니다.
     * <b>리포트 계약이 도착하면 실제 키 목록을 여기 적어두세요.</b>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metrics;
}
