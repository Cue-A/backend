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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/**
 * 질문은행 「공통 질문」 탭의 질문 한 개.
 *
 * <p>{@code user} 가 null 이면 모두가 보는 기본 질문(V5 시드)이고, 있으면 그 사용자가 직접 추가한 내 질문입니다.
 * 내 질문은 주인에게만 보이고 주인만 고치고 지울 수 있습니다. 기본 질문은 API 로 바꿀 수 없습니다.
 *
 * <p>즐겨찾기 · 메모는 사람마다 달라 {@link CommonQuestionNote} 에 둡니다.
 */
@Entity
@Table(
        name = "common_question",
        indexes = @Index(name = "idx_common_question_user", columnList = "user_id, created_at")
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonQuestion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "common_question_id")
    private Long commonQuestionId;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** null 이면 기본 질문입니다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false, length = 200)
    private String text;

    /** 인성 · 경험 · 지원동기 · 직무 · 기타. 면접 질문의 AI 분류와 다른 목록입니다. */
    @Column(nullable = false, length = 10)
    private String category;

    /** 사용자가 직접 추가한 질문인지. 지연 로딩된 {@code user} 를 초기화하지 않습니다. */
    public boolean isCustom() {
        return user != null;
    }

    public void edit(String text, String category) {
        this.text = text;
        this.category = category;
    }
}
