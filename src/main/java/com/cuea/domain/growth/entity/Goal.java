package com.cuea.domain.growth.entity;

import com.cuea.common.entity.BaseTimeEntity;
import com.cuea.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 사용자가 스스로 건 목표. "이번 달에 세션 10회" 같은 것입니다.
 *
 * <p>{@code targetMetric} 이 무엇을 셀지, {@code targetValue} 가 그 목표치입니다.
 * 지표를 늘려도 스키마를 안 바꾸려고 컬럼 두 개로 일반화되어 있습니다.
 *
 * <p>{@code goalType}, {@code targetMetric}, {@code status} 의 값 목록이 ERD 에
 * 없어 문자열로 뒀습니다. 목록이 정해지면 enum 으로 올리세요.
 */
@Entity
@Table(
        name = "goal",
        indexes = @Index(name = "idx_goal_user_status", columnList = "user_id, status")
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Goal extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "goal_id")
    private Long goalId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /** 목표의 성격. 주간·월간 같은 주기 구분입니다. */
    @Column(name = "goal_type", nullable = false, length = 20)
    private String goalType;

    /** 무엇을 세는지. 세션 수, 답변 수, 평균 점수 등. */
    @Column(name = "target_metric", nullable = false, length = 30)
    private String targetMetric;

    @Column(name = "target_value", nullable = false)
    private int targetValue;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, length = 20)
    private String status;

    /** 달성한 시각. 아직 달성 못 했으면 null. */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public void complete() {
        this.completedAt = OffsetDateTime.now();
    }
}
