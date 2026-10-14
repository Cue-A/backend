package com.cuea.domain.calendar.entity;

import com.cuea.common.entity.BaseTimeEntity;
import com.cuea.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.OffsetDateTime;

/**
 * 사용자가 직접 등록하는 실제 면접 일정.
 *
 * <p><b>{@code companyName} 은 문자열이고 {@code company} 테이블 FK 가 아닙니다.</b>
 * 우리가 수집해둔 기업 목록에 없는 회사에도 지원하기 때문입니다. FK 로 묶으면
 * 목록에 없는 회사의 일정을 아예 등록할 수 없게 됩니다.
 *
 * <p>{@code location} 과 {@code meetingUrl} 은 {@link InterviewMethod} 에 따라
 * 한쪽만 채워집니다. 둘 다 nullable 인 이유가 이것입니다.
 */
@Entity
@Table(
        name = "calendar_event",
        indexes = @Index(name = "idx_calendar_event_user_scheduled", columnList = "user_id, scheduled_at")
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalendarEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "calendar_event_id")
    private Long calendarEventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /** 사용자가 직접 입력한 회사명. {@code company} 테이블과 연결되지 않습니다. */
    @Column(name = "company_name", nullable = false, length = 100)
    private String companyName;

    @Column(name = "job_role", nullable = false, length = 50)
    private String jobRole;

    /** "1차", "임원" 같은 자유 입력. */
    @Column(name = "interview_round", length = 30)
    private String interviewRound;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_method", nullable = false, length = 10)
    private InterviewMethod interviewMethod;

    /** {@link InterviewMethod#OFFLINE} 일 때 채웁니다. */
    @Column(length = 200)
    private String location;

    /** {@link InterviewMethod#ONLINE} 일 때 채웁니다. */
    @Column(name = "meeting_url", length = 500)
    private String meetingUrl;

    @Column(columnDefinition = "text")
    private String memo;

    public void reschedule(OffsetDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public void editMemo(String memo) {
        this.memo = memo;
    }
}
