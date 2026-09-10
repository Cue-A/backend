package com.cuea.domain.interview.entity;

import com.cuea.common.entity.BaseCreatedEntity;
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
 * 사용자가 면접 세션을 묶어두는 폴더. "네이버 상반기" 같은 것입니다.
 *
 * <p><b>문서가 아니라 세션을 담습니다.</b> 이름만 보면 문서 폴더로 오해하기 쉬운데
 * {@code document} 에는 {@code folder_id} 가 없고 {@link InterviewSession} 에만
 * 있습니다.
 *
 * <p>폴더를 지워도 세션은 지우지 않습니다. 세션의 {@code folder_id} 만 null 이 되어
 * "폴더 없음"으로 남습니다. 연습 기록이 폴더 정리 한 번에 날아가면 안 됩니다.
 */
@Entity
@Table(
        name = "folder",
        indexes = @Index(name = "idx_folder_user", columnList = "user_id")
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Folder extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "folder_id")
    private Long folderId;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "folder_name", nullable = false, length = 50)
    private String folderName;

    public void rename(String folderName) {
        this.folderName = folderName;
    }
}
