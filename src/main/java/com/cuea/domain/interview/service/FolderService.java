package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.FolderListResponse;
import com.cuea.domain.interview.dto.response.FolderResponse;
import com.cuea.domain.interview.entity.Folder;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.FolderRepository;
import com.cuea.domain.interview.repository.FolderSessionCount;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 연습 기록 폴더 목록 · 만들기 · 이름 바꾸기 · 지우기.
 *
 * <p>이름 중복과 개수 상한은 문서 상한처럼 여기서만 검사합니다. DB 제약이 없어 같은 요청이
 * 동시에 두 번 오면 둘 다 통과할 수 있습니다.
 */
@Service
@RequiredArgsConstructor
public class FolderService {

    static final int MAX_FOLDERS_PER_USER = 20;

    private final FolderRepository folderRepository;
    private final InterviewSessionRepository sessionRepository;
    private final UserRepository userRepository;

    /** 만든 순서대로. 세션 수는 연습 기록 목록과 같은 기준입니다. */
    @Transactional(readOnly = true)
    public FolderListResponse list(String userId) {
        Map<Long, Long> counts = sessionRepository.countByFolder(userId, SessionStatus.LISTED).stream()
                .collect(Collectors.toMap(FolderSessionCount::folderId, FolderSessionCount::sessionCount));
        List<FolderResponse> folders = folderRepository.findAllByUser_UserIdOrderByCreatedAtAscFolderIdAsc(userId)
                .stream()
                .map(f -> FolderResponse.of(f, counts.getOrDefault(f.getFolderId(), 0L)))
                .toList();
        long total = sessionRepository.countByUser_UserIdAndStatusIn(userId, SessionStatus.LISTED);
        return new FolderListResponse(total, folders);
    }

    @Transactional
    public FolderResponse create(String userId, String folderName) {
        String name = FolderName.normalize(folderName);
        if (folderRepository.countByUser_UserId(userId) >= MAX_FOLDERS_PER_USER) {
            throw new BusinessException(ErrorCode.FOLDER_LIMIT_EXCEEDED);
        }
        validateNameNotTaken(userId, name);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Folder folder = folderRepository.save(Folder.builder()
                .publicId(UUID.randomUUID())
                .user(user)
                .folderName(name)
                .build());
        return FolderResponse.of(folder, 0);
    }

    /** 지금 이름과 같으면 중복 검사 없이 그대로 성공합니다. */
    @Transactional
    public FolderResponse rename(String userId, String folderId, String folderName) {
        String name = FolderName.normalize(folderName);
        Folder folder = getOwnedFolder(userId, folderId);
        if (!folder.getFolderName().equals(name)) {
            validateNameNotTaken(userId, name);
            folder.rename(name);
        }
        return FolderResponse.of(folder,
                sessionRepository.countByFolder_FolderIdAndStatusIn(folder.getFolderId(), SessionStatus.LISTED));
    }

    /** 안의 세션은 지우지 않습니다. 세션은 폴더에서만 빠져 「전체」에 남습니다. */
    @Transactional
    public void delete(String userId, String folderId) {
        folderRepository.delete(getOwnedFolder(userId, folderId));
    }

    private void validateNameNotTaken(String userId, String name) {
        if (folderRepository.existsByUser_UserIdAndFolderName(userId, name)) {
            throw new BusinessException(ErrorCode.FOLDER_NAME_DUPLICATED);
        }
    }

    /** 없는 폴더 · 남의 폴더 · UUID 가 아닌 값은 구별하지 않고 404 입니다. */
    private Folder getOwnedFolder(String userId, String folderId) {
        return folderRepository.findByPublicIdAndUser_UserId(parsePublicId(folderId), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FOLDER_NOT_FOUND));
    }

    private static UUID parsePublicId(String folderId) {
        try {
            return UUID.fromString(folderId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.FOLDER_NOT_FOUND);
        }
    }
}
