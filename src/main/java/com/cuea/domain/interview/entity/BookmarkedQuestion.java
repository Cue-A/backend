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
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 다시 보려고 담아둔 질문. 메모를 붙일 수 있습니다.
 *
 * <p>{@link Question#isBookmarked()} 플래그와 짝입니다. 이 행을 만들거나 지울 때
 * 반드시 그 플래그도 같이 바꾸세요. 둘이 어긋나면 목록과 상세가 다르게 보입니다.
 *
 * <p>UNIQUE {@code (user_id, session_id, question_id)} 는 ERD 에 없지만 넣었습니다.
 * 같은 질문을 두 번 담는 것은 기능이 아니라 버그입니다.
 */
@Entity
@Table(
        name = "bookmarked_question",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bookmarked_question",
                columnNames = {"user_id", "session_id", "question_id"}
        ),
        indexes = @Index(name = "idx_bookmarked_question_user", columnList = "user_id, created_at")
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookmarkedQuestion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bookmarked_question_id")
    private Long bookmarkedQuestionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "session_id", referencedColumnName = "session_id", nullable = false),
            @JoinColumn(name = "question_id", referencedColumnName = "question_id", nullable = false)
    })
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Question question;

    @Column(length = 500)
    private String memo;

    public void editMemo(String memo) {
        this.memo = memo;
    }
}
