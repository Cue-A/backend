package com.cuea.domain.user.entity;

import com.cuea.common.entity.BaseTimeEntity;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 알림 수신 여부. {@link User} 와 1:1 입니다.
 *
 * <p>전부 {@code NOT NULL} 이라 행이 없으면 "설정 안 함"과 "끔"을 구분할 수
 * 없습니다. 가입 시점에 기본값으로 한 행을 만들어 두세요.
 *
 * <p>{@code marketing_enabled} 만 기본값이 false 입니다. 광고성 정보는 수신
 * 동의를 받아야 보낼 수 있습니다.
 */
@Entity
@Table(name = "notification_setting")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_setting_id")
    private Long notificationSettingId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /** 면접 일정 알림. */
    @Column(name = "calendar_enabled", nullable = false)
    private boolean calendarEnabled;

    /** 목표 달성·마감 알림. */
    @Column(name = "goal_enabled", nullable = false)
    private boolean goalEnabled;

    /** 리포트 생성 완료 알림. */
    @Column(name = "report_enabled", nullable = false)
    private boolean reportEnabled;

    /** 배지 획득 알림. */
    @Column(name = "badge_enabled", nullable = false)
    private boolean badgeEnabled;

    /** 연속 답변 유지 알림. */
    @Column(name = "streak_enabled", nullable = false)
    private boolean streakEnabled;

    /** 광고성 정보. 별도 동의가 필요합니다. */
    @Column(name = "marketing_enabled", nullable = false)
    private boolean marketingEnabled;

    /** 가입 시 만들어 두는 기본 설정. 마케팅만 꺼진 상태입니다. */
    public static NotificationSetting defaultsFor(User user) {
        return NotificationSetting.builder()
                .user(user)
                .calendarEnabled(true)
                .goalEnabled(true)
                .reportEnabled(true)
                .badgeEnabled(true)
                .streakEnabled(true)
                .marketingEnabled(false)
                .build();
    }
}
