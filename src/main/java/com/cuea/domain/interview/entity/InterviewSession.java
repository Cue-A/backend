package com.cuea.domain.interview.entity;

import com.cuea.common.entity.BaseTimeEntity;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
 * 면접 한 판. 질문·답변·리포트가 전부 이 아래에 달립니다.
 *
 * <p><b>클래스명은 {@code InterviewSession} 이고 테이블명은 {@code session} 입니다.</b>
 * 자바 쪽에서 {@code Session} 이라는 이름을 쓰지 않았습니다.
 * {@code org.hibernate.Session}, {@code jakarta.websocket.Session}, 이 저장소의
 * {@code SocketSessionRegistry} 까지 겹쳐서 import 한 줄로 의미가 뒤집히는 이름입니다.
 *
 * <p><b>PK 를 우리가 만들지 않습니다.</b> AI 서버가 발급한 세션 ID 를 그대로 씁니다
 * ({@code docs/02-database.md}). 그래서 {@code @GeneratedValue} 가 없고, 저장 전에
 * 반드시 AI 응답의 {@code session_id} 를 넣어야 합니다.
 *
 * <p>ERD 초안은 {@code bigint} 자동증가 + {@code public_id uuid} 였지만 그러면
 * AI 가 준 세션 ID 를 둘 곳이 없어집니다. 문서 쪽을 따랐고, 이 값이 곧 외부
 * 노출 식별자라 {@code public_id} 는 두지 않습니다.
 *
 * <p>{@code company}, {@code document}, {@code folder} 가 모두 nullable 입니다.
 * 회사를 고르지 않고 연습만 하거나, 문서 없이 직무만으로 보거나, 폴더에 넣지 않은
 * 세션이 정상적으로 존재합니다.
 */
@Entity
@Table(
        name = "session",
        indexes = {
                @Index(name = "idx_session_user", columnList = "user_id, created_at"),
                @Index(name = "idx_session_retry_root", columnList = "retry_of_session_id"),
                @Index(name = "idx_session_folder", columnList = "folder_id")
        }
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewSession extends BaseTimeEntity {

    /** AI 서버가 발급한 값을 그대로 씁니다. 우리가 만들지 않습니다. */
    @Id
    @Column(name = "session_id", length = 50)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /** 회사 미선택 연습 모드면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    /** 문서 없이 직무만으로 보는 세션이면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private Document document;

    /** 폴더에 넣지 않은 세션이면 null. 폴더가 지워져도 null 이 됩니다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Folder folder;

    /**
     * 재연습의 기준 세션. <b>직전 회차가 아니라 항상 최초 세션입니다.</b>
     *
     * <p>3회차에서 이 값은 2회차가 아니라 1회차를 가리킵니다. 회차 목록은
     * "같은 루트를 가진 세션 전부"로 뽑으며 {@code idx_session_retry_root} 가
     * 그 조회용입니다. 직전 회차로 채우면 회차가 사슬로 늘어져 목록 조회가
     * 재귀 쿼리가 됩니다.
     *
     * <p>ERD 초안의 이름은 {@code prev_session_id} 였지만 직전 회차로 읽혀
     * 문서 쪽 이름을 따랐습니다. 자세한 것은 {@code docs/12-replay.md}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retry_of_session_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private InterviewSession retryOfSession;

    /** 면접 진행 방식. 값 목록 미확정이라 문자열로 둡니다. */
    @Column(nullable = false, length = 20)
    private String mode;

    @Column(name = "job_role", nullable = false, length = 50)
    private String jobRole;

    /** 회사를 고르지 않고 인재상을 직접 적은 경우의 원문. */
    @Column(name = "custom_talent", columnDefinition = "text")
    private String customTalent;

    /**
     * 사용자가 고른 문항 수. 3 · 6 · 9 중 하나이며 기본 6.
     *
     * <p>되묻기({@code REASK})는 여기에 포함되지 않습니다. 답변이 부실해
     * 꼬리질문이 생략되면 예비 토픽을 넣어 이 수를 채웁니다.
     */
    @Column(name = "question_count", nullable = false)
    private int questionCount;

    /** 압박 강도. 값 목록 미확정이라 문자열로 둡니다. */
    @Column(name = "pressure_level", nullable = false, length = 10)
    private String pressureLevel;

    /** 질문 텍스트를 숨기고 음성만 들려주는 실전 모드인지. */
    @Column(name = "hide_question_text", nullable = false)
    private boolean hideQuestionText;

    /** 답변 제한 시간(초). 제한 없음이면 null. */
    @Column(name = "duration_limit")
    private Integer durationLimit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status;

    /** {@code session_end} 를 받은 시각. 진행 중이거나 중단됐으면 null. */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** {@code session_end} 수신. 여기서부터 리포트를 만들 수 있습니다. */
    public void complete() {
        this.status = SessionStatus.COMPLETED;
        this.completedAt = OffsetDateTime.now();
    }

    /** 사용자 중단 · 타임아웃 · 복구 불가 오류. 리포트를 만들지 않습니다. */
    public void abort() {
        this.status = SessionStatus.ABORTED;
    }

    public void moveTo(Folder folder) {
        this.folder = folder;
    }

    /** 재연습 세션인지. 1회차면 false 입니다. */
    public boolean isRetry() {
        return retryOfSession != null;
    }

    /** 회차 묶음의 루트 세션 ID. 1회차면 자기 자신입니다. */
    public String rootSessionId() {
        return retryOfSession == null ? sessionId : retryOfSession.getSessionId();
    }
}
