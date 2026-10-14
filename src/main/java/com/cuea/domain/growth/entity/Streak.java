package com.cuea.domain.growth.entity;

import com.cuea.common.entity.BaseTimeEntity;
import com.cuea.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;

/**
 * 하루치 활동 기록. 잔디밭과 연속 일수를 여기서 셉니다.
 *
 * <p><b>연속 일수를 저장하지 않습니다.</b> 날짜별 행만 쌓고 연속 여부는 조회할 때
 * 계산합니다. 숫자를 들고 있으면 자정 넘어가는 요청, 시간대, 중간 실패 때
 * 실제 기록과 어긋나기 시작합니다.
 *
 * <p>UNIQUE {@code (user_id, activity_date)} 가 하루 한 행을 보장합니다.
 * 같은 날 또 답변하면 새 행을 만들지 말고 {@link #countAnswer()} 로 올리세요.
 */
@Entity
@Table(
        name = "streak",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_streak_user_date",
                columnNames = {"user_id", "activity_date"}
        )
)
@Check(name = "ck_streak_answer_count", constraints = "answer_count >= 1")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Streak extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "streak_id")
    private Long streakId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /** 답변한 날짜. 행이 있다는 것 자체가 그날 활동했다는 뜻입니다. */
    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    /** 그날 답변 수. 1 미만이면 행이 없어야 하므로 CHECK 로 막습니다. */
    @Column(name = "answer_count", nullable = false)
    private int answerCount;

    /** 같은 날 추가 답변. 새 행을 만들지 말고 이걸 쓰세요. */
    public void countAnswer() {
        this.answerCount++;
    }
}
