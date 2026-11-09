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
 * 사용자가 직접 등록하는 면접 일정.
 *
 * <p>프론트 일정 등록 모달(Issue #130)은 <b>제목 · 날짜 · 선택적 시간 · 선택적 메모</b>만
 * 받습니다. 그래서 {@code title} 과 {@code timeSpecified} 가 핵심 칸입니다.
 *
 * <p>{@code timeSpecified} 는 "시간 미정"(그날 00:00, false)과 "자정 면접"(00:00, true)을
 * 구분합니다. {@code scheduled_at} 은 {@code timestamptz} 라 UTC 로 정규화되고, 날짜만 있는
 * 일정은 프론트가 그 사람 기준 그날 00:00 으로 보냅니다.
 *
 * <p>{@code companyName} · {@code jobRole} · {@code interviewMethod} 는 과거 "실제 면접 일정"
 * 모델의 잔재로, 현재 프론트 모달은 보내지 않습니다(V3 에서 NOT NULL 완화). 기존 데이터 호환을
 * 위해 컬럼은 남겨 두되 새 API 는 채우지 않습니다. {@code companyName} 은 문자열이고
 * {@code company} 테이블 FK 가 아닙니다 — 목록에 없는 회사도 적을 수 있어야 하기 때문입니다.
 *
 * <p>{@code location} 과 {@code meetingUrl} 도 과거 모델의 칸이라 새 API 에서는 쓰지 않습니다.
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

    /** 일정 제목. 사용자 입력 필수(1~50자). */
    @Column(name = "title", nullable = false, length = 50)
    private String title;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    /** 시간을 지정한 일정이면 true, 날짜만 있는 일정이면 false(그날 00:00). */
    @Column(name = "time_specified", nullable = false)
    private boolean timeSpecified;

    @Column(columnDefinition = "text")
    private String memo;

    /** 과거 "실제 면접 일정" 모델의 칸. 현재 등록 모달은 보내지 않습니다(V3 에서 nullable). */
    @Column(name = "company_name", length = 100)
    private String companyName;

    @Column(name = "job_role", length = 50)
    private String jobRole;

    /** "1차", "임원" 같은 자유 입력. 현재 등록 모달은 보내지 않습니다. */
    @Column(name = "interview_round", length = 30)
    private String interviewRound;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_method", length = 10)
    private InterviewMethod interviewMethod;

    /** {@link InterviewMethod#OFFLINE} 일 때 채웁니다. 현재 등록 모달은 보내지 않습니다. */
    @Column(length = 200)
    private String location;

    /** {@link InterviewMethod#ONLINE} 일 때 채웁니다. 현재 등록 모달은 보내지 않습니다. */
    @Column(name = "meeting_url", length = 500)
    private String meetingUrl;

    /**
     * 등록 모달에서 들어오는 일정을 만듭니다. 검증(제목 trim·길이, 메모 길이, scheduledAt 파싱)은
     * 서비스가 끝낸 값을 받습니다.
     */
    public static CalendarEvent create(User user, String title, OffsetDateTime scheduledAt,
                                       boolean timeSpecified, String memo) {
        return CalendarEvent.builder()
                .user(user)
                .title(title)
                .scheduledAt(scheduledAt)
                .timeSpecified(timeSpecified)
                .memo(memo)
                .build();
    }

    /**
     * 일정 전체 수정(PUT). 등록과 같은 네 칸을 바꿉니다. company/jobRole/method 등 과거 모델 칸은
     * 건드리지 않아 기존 데이터가 보존됩니다.
     */
    public void update(String title, OffsetDateTime scheduledAt, boolean timeSpecified, String memo) {
        this.title = title;
        this.scheduledAt = scheduledAt;
        this.timeSpecified = timeSpecified;
        this.memo = memo;
    }

    public void reschedule(OffsetDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public void editMemo(String memo) {
        this.memo = memo;
    }
}
