package com.cuea.domain.interview.entity;

import com.cuea.common.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
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

/**
 * AI 가 던진 질문 하나와 그 답변 녹음. 리포트와 재연습의 유일한 근거입니다.
 *
 * <p><b>수신 즉시 저장합니다.</b> 프론트에 push 한 뒤에 저장하면 그 사이에
 * 브라우저를 닫은 사용자의 기록이 통째로 사라집니다.
 *
 * <p><b>PK 가 {@code (sessionId, questionId)} 복합키입니다.</b> AI 가 주는
 * {@code questionId} 는 {@code "q_1"}, {@code "q_4r"} 처럼 세션 스코프라 다른
 * 세션과 충돌합니다. {@link QuestionId} 참고.
 *
 * <p>ERD 초안에는 {@code type}·{@code isSpareTopic}·{@code isReplay}·
 * {@code topicIndex}·{@code audioUrl} 이 없어 되묻기 저장과 회차 비교가 불가능했습니다.
 * docs/11-interview.md · docs/12-replay.md 기준으로 되살렸습니다.
 */
@Entity
@Table(
        name = "question",
        indexes = @Index(name = "idx_qlog_session", columnList = "session_id, question_number")
)
@IdClass(QuestionId.class)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Question extends BaseCreatedEntity {

    @Id
    @Column(name = "session_id", length = 50)
    private String sessionId;

    /** AI 가 준 세션 스코프 ID. {@code "q_1"}, {@code "q_4r"} 형태입니다. */
    @Id
    @Column(name = "question_id", length = 50)
    private String questionId;

    /** 조인 전용. 값은 {@link #sessionId} 가 들고 있어 쓰기에 관여하지 않습니다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private InterviewSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionType type;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    /** 질문 TTS 음성. {@code TTS_FAILED} 면 null 이고 텍스트만 보여줍니다. */
    @Column(name = "audio_url", columnDefinition = "text")
    private String audioUrl;

    /**
     * AI 가 정의한 8종 카테고리. 되묻기면 null. <b>절대 자바 enum 으로 바꾸지 마세요.</b>
     *
     * <pre>
     * 지원동기  직무역량  프로젝트경험  문제해결
     * 협업·갈등  실패·성장  가치관·인성  미래계획
     * </pre>
     *
     * <p>가운뎃점(·)까지 정확히 일치해야 하고, 재연습 요청 때 값이 다르면 AI 가
     * {@code INVALID_CATEGORY} 를 돌려줍니다. 한글이라 변환 과정에서 깨지기 쉽고,
     * AI 가 카테고리를 추가할 때 백엔드 배포 없이는 못 받게 됩니다.
     * <b>받은 문자열을 그대로 저장하고 그대로 되돌려줍니다.</b>
     */
    @Column(length = 20)
    private String category;

    /** {@code L1} ~ {@code L3}. 되묻기면 null. 재연습 때 꼬리질문 난이도까지 맞춰야 해서 저장합니다. */
    @Column(length = 5)
    private String difficulty;

    /**
     * 계획된 토픽이 아니라 문항 수를 채우려고 투입된 토픽의 질문인지.
     *
     * <p>{@link #replay} 와 이름이 비슷하지만 축이 다릅니다. 이쪽은 세션 내부
     * 구조이며 통계·디버깅용입니다. <b>회차 비교 필터에 쓰지 마세요.</b>
     */
    @Column(name = "is_spare_topic", nullable = false)
    private boolean spareTopic;

    /**
     * 1회차와 동일한 질문인지. <b>회차 비교 화면은 이것만 봅니다.</b>
     *
     * <p>{@link #spareTopic} 과 헷갈리기 쉽습니다. 그쪽은 세션 내부 구조,
     * 이쪽은 회차 간 관계입니다.
     */
    @Column(name = "is_replay", nullable = false)
    private boolean replay;

    /**
     * 진행률에 쓰는 문항 번호. "질문 4 / 9" 의 4 입니다.
     *
     * <p><b>되묻기에서는 올라가지 않습니다.</b> 그래서 이 값은 세션 안에서
     * 유일하지 않고, UNIQUE 제약을 걸면 되묻기 저장이 실패합니다.
     */
    @Column(name = "question_number", nullable = false)
    private int questionNumber;

    /**
     * 몇 번째 토픽인지. <b>진행률 표시에 쓰지 마세요.</b>
     *
     * <p>토픽 수는 세션마다 다릅니다. 부실하게 답할수록 늘어나서 "주제 2 / 4" 는
     * 도중에 분모가 바뀝니다. 참고·디버깅용입니다.
     */
    @Column(name = "topic_index", nullable = false)
    private int topicIndex;

    /**
     * 사용자 답변 녹음 위치. 아직 답하지 않았으면 null.
     *
     * <p>답변 업로드가 끝난 시점에 채웁니다. 그 시점에는 아직 AI 분석 전이라
     * {@link Answer} 행이 없어서, 분석 결과가 아니라 여기에 둡니다.
     */
    @Column(name = "answer_audio_url", columnDefinition = "text")
    private String answerAudioUrl;

    /**
     * 사용자가 즐겨찾기 했는지.
     *
     * <p>{@link BookmarkedQuestion} 과 중복되는 비정규화 플래그입니다. 질문 목록을
     * 뿌릴 때 조인을 피하려는 용도이며 <b>둘을 항상 같이 갱신해야 합니다.</b>
     */
    @Column(name = "is_bookmarked", nullable = false)
    private boolean bookmarked;

    /** 답변 녹음 업로드 완료. */
    public void attachAnswerAudio(String answerAudioUrl) {
        this.answerAudioUrl = answerAudioUrl;
    }

    public void markBookmarked(boolean bookmarked) {
        this.bookmarked = bookmarked;
    }

    /** 문항 수에 포함되는 질문인지. 되묻기만 제외됩니다. */
    public boolean countsTowardQuestionCount() {
        return type.countsTowardQuestionCount();
    }
}
