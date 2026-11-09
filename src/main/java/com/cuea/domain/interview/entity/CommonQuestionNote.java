package com.cuea.domain.interview.entity;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;

/**
 * 공통 질문 한 개에 대한 내 즐겨찾기 · 메모.
 *
 * <p>즐겨찾기를 풀어도 행과 메모는 남습니다. 다시 즐겨찾기할 수 있는 질문이라 메모를 같이 지우지 않습니다.
 * 내 질문을 지우면 DB 의 {@code ON DELETE CASCADE} 가 이 행도 지웁니다.
 */
@Entity
@Table(
        name = "common_question_note",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_common_question_note",
                columnNames = {"user_id", "common_question_id"}
        ),
        indexes = @Index(name = "idx_common_question_note_question", columnList = "common_question_id")
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonQuestionNote extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "common_question_note_id")
    private Long commonQuestionNoteId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "common_question_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommonQuestion commonQuestion;

    @Column(nullable = false)
    private boolean favorite;

    /** 즐겨찾기를 켠 시각. 꺼져 있으면 null 입니다. */
    @Column(name = "favorited_at")
    private OffsetDateTime favoritedAt;

    @Column(length = 500)
    private String memo;

    /**
     * 바뀔 상태로 맞춥니다. 이미 그 상태면 아무것도 바꾸지 않아, 켜져 있던 질문은 처음 켠 시각이 남습니다.
     * 프론트는 이 시각의 최신순으로 즐겨찾기를 위에 올립니다.
     */
    public void markFavorite(boolean favorite, OffsetDateTime now) {
        if (this.favorite == favorite) {
            return;
        }
        this.favorite = favorite;
        this.favoritedAt = favorite ? now : null;
    }

    public void editMemo(String memo) {
        this.memo = memo;
    }
}
