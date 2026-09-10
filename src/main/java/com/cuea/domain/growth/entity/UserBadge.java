package com.cuea.domain.growth.entity;

import com.cuea.domain.interview.entity.InterviewSession;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;

/**
 * 사용자가 배지를 획득한 기록.
 *
 * <p>시간 컬럼이 {@code created_at} 이 아니라 {@code earned_at} 하나뿐이라
 * 공통 부모를 상속하지 않고 직접 선언했습니다.
 *
 * <p>{@code sourceSession} 은 어느 세션에서 땄는지이며 선택입니다. 연속 답변처럼
 * 특정 세션과 무관하게 주는 배지가 있어 nullable 이고, 그 세션이 지워져도
 * 획득 기록은 남아야 해서 {@code SET NULL} 입니다.
 */
@Entity
@Table(
        name = "user_badge",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_badge",
                columnNames = {"user_id", "badge_id"}
        ),
        indexes = {
                @Index(name = "idx_user_badge_user_earned", columnList = "user_id, earned_at"),
                @Index(name = "idx_user_badge_badge", columnList = "badge_id")
        }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserBadge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_badge_id")
    private Long userBadgeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /** 배지를 지우려 해도 이 FK 가 막습니다. 지우지 말고 상태를 내리세요. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "badge_id", nullable = false)
    private Badge badge;

    /** 획득 계기가 된 세션. 세션과 무관한 배지면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_session_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private InterviewSession sourceSession;

    @Column(name = "earned_at", nullable = false, updatable = false)
    private OffsetDateTime earnedAt;

    @PrePersist
    void applyEarnedAt() {
        if (earnedAt == null) {
            earnedAt = OffsetDateTime.now();
        }
    }
}
