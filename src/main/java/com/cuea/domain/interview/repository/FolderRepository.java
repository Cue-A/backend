package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.Folder;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 소유자가 있는 엔티티라 {@code findById} 가 없습니다. 조회는 항상 소유자와 함께 겁니다.
 * docs/01-conventions.md 의 "소유자 있는 엔티티는 Repository 를 상속합니다" 참고.
 */
public interface FolderRepository extends Repository<Folder, Long> {

    Optional<Folder> findByPublicIdAndUser_UserId(UUID publicId, String userId);

    /** 만든 순서. 같은 시각이면 PK 순으로 고정합니다. */
    List<Folder> findAllByUser_UserIdOrderByCreatedAtAscFolderIdAsc(String userId);

    long countByUser_UserId(String userId);

    boolean existsByUser_UserIdAndFolderName(String userId, String folderName);

    Folder save(Folder folder);

    /** 세션의 {@code folder_id} 는 DB 의 {@code ON DELETE SET NULL} 이 비웁니다. */
    void delete(Folder folder);
}
