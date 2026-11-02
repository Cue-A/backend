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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 폴더 이름 · 개수 규칙과 404 처리를 DB 없이 봅니다. 쿼리 자체는 FolderRepositoryTest 가 봅니다. */
class FolderServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID PUBLIC_ID = UUID.randomUUID();
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-07-01T00:00:00Z");

    private FolderRepository folderRepository;
    private InterviewSessionRepository sessionRepository;
    private UserRepository userRepository;
    private FolderService service;

    @BeforeEach
    void setUp() {
        folderRepository = mock(FolderRepository.class);
        sessionRepository = mock(InterviewSessionRepository.class);
        userRepository = mock(UserRepository.class);
        service = new FolderService(folderRepository, sessionRepository, userRepository);
        when(folderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(User.create("kim@example.com", "김취준")));
    }

    @Test
    void 이름의_앞뒤_공백을_잘라_만든다() {
        FolderResponse response = service.create(USER_ID, "  삼성전자  ");

        ArgumentCaptor<Folder> saved = ArgumentCaptor.forClass(Folder.class);
        verify(folderRepository).save(saved.capture());
        assertThat(saved.getValue().getFolderName()).isEqualTo("삼성전자");
        assertThat(saved.getValue().getPublicId()).isNotNull();
        assertThat(response.folderName()).isEqualTo("삼성전자");
        assertThat(response.sessionCount()).isZero();
    }

    @Test
    void 이름이_비었거나_50자를_넘으면_INVALID_REQUEST() {
        assertErrorCode(() -> service.create(USER_ID, "   "), ErrorCode.INVALID_REQUEST);
        assertErrorCode(() -> service.create(USER_ID, null), ErrorCode.INVALID_REQUEST);
        assertErrorCode(() -> service.create(USER_ID, "가".repeat(51)), ErrorCode.INVALID_REQUEST);
        verify(folderRepository, never()).save(any());
    }

    /** 50자는 앞뒤 공백을 자른 뒤 셉니다. */
    @Test
    void 공백을_자르고_50자면_만든다() {
        assertThat(service.create(USER_ID, "  " + "가".repeat(50) + "  ").folderName()).hasSize(50);
    }

    @Test
    void 같은_이름이_있으면_FOLDER_NAME_DUPLICATED() {
        when(folderRepository.existsByUser_UserIdAndFolderName(USER_ID, "삼성전자")).thenReturn(true);

        assertErrorCode(() -> service.create(USER_ID, " 삼성전자 "), ErrorCode.FOLDER_NAME_DUPLICATED);
        verify(folderRepository, never()).save(any());
    }

    @Test
    void 이미_20개면_FOLDER_LIMIT_EXCEEDED() {
        when(folderRepository.countByUser_UserId(USER_ID)).thenReturn(20L);

        assertErrorCode(() -> service.create(USER_ID, "새 폴더"), ErrorCode.FOLDER_LIMIT_EXCEEDED);
        verify(folderRepository, never()).save(any());
    }

    @Test
    void 이름을_바꾸고_세션_수를_함께_준다() {
        Folder folder = givenFolder("삼성전자");
        when(sessionRepository.countByFolder_FolderIdAndStatusIn(10L, SessionStatus.LISTED)).thenReturn(5L);

        FolderResponse response = service.rename(USER_ID, PUBLIC_ID.toString(), " 삼성 하반기 ");

        assertThat(folder.getFolderName()).isEqualTo("삼성 하반기");
        assertThat(response.folderName()).isEqualTo("삼성 하반기");
        assertThat(response.sessionCount()).isEqualTo(5);
    }

    /** 자기 이름은 중복 검사에 걸리면 안 됩니다. */
    @Test
    void 지금_이름과_같으면_중복_검사_없이_성공한다() {
        givenFolder("삼성전자");

        assertThat(service.rename(USER_ID, PUBLIC_ID.toString(), "삼성전자 ").folderName()).isEqualTo("삼성전자");
        verify(folderRepository, never()).existsByUser_UserIdAndFolderName(anyString(), anyString());
    }

    @Test
    void 다른_폴더가_쓰는_이름으로는_못_바꾼다() {
        Folder folder = givenFolder("삼성전자");
        when(folderRepository.existsByUser_UserIdAndFolderName(USER_ID, "카카오")).thenReturn(true);

        assertErrorCode(() -> service.rename(USER_ID, PUBLIC_ID.toString(), "카카오"), ErrorCode.FOLDER_NAME_DUPLICATED);
        assertThat(folder.getFolderName()).isEqualTo("삼성전자");
    }

    /** 남의 폴더도 소유자 조건이 걸린 조회에서 빈 값이라 여기로 옵니다. */
    @Test
    void 없거나_남의_폴더면_FOLDER_NOT_FOUND() {
        when(folderRepository.findByPublicIdAndUser_UserId(PUBLIC_ID, USER_ID)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.rename(USER_ID, PUBLIC_ID.toString(), "카카오"), ErrorCode.FOLDER_NOT_FOUND);
        assertErrorCode(() -> service.delete(USER_ID, PUBLIC_ID.toString()), ErrorCode.FOLDER_NOT_FOUND);
        verify(folderRepository, never()).delete(any());
    }

    @Test
    void UUID_가_아니면_조회하지_않고_FOLDER_NOT_FOUND() {
        assertErrorCode(() -> service.delete(USER_ID, "not-a-uuid"), ErrorCode.FOLDER_NOT_FOUND);
        verify(folderRepository, never()).findByPublicIdAndUser_UserId(any(), anyString());
    }

    @Test
    void 지우면_그_폴더를_삭제한다() {
        Folder folder = givenFolder("삼성전자");

        service.delete(USER_ID, PUBLIC_ID.toString());

        verify(folderRepository).delete(folder);
    }

    /** 세션 수 쿼리는 세션이 있는 폴더만 돌려주므로 나머지는 0 으로 채웁니다. */
    @Test
    void 목록은_세션이_없는_폴더를_0으로_채운다() {
        Folder samsung = folder(1L, UUID.randomUUID(), "삼성전자");
        Folder etc = folder(2L, UUID.randomUUID(), "기타");
        when(folderRepository.findAllByUser_UserIdOrderByCreatedAtAscFolderIdAsc(USER_ID))
                .thenReturn(List.of(samsung, etc));
        when(sessionRepository.countSessionsPerFolder(USER_ID, SessionStatus.LISTED))
                .thenReturn(List.of(new FolderSessionCount(1L, 5L)));
        when(sessionRepository.countByUser_UserIdAndStatusIn(USER_ID, SessionStatus.LISTED)).thenReturn(12L);

        FolderListResponse response = service.list(USER_ID);

        assertThat(response.totalCount()).isEqualTo(12);
        assertThat(response.folders()).extracting(FolderResponse::folderName, FolderResponse::sessionCount)
                .containsExactly(tuple("삼성전자", 5L), tuple("기타", 0L));
    }

    private Folder givenFolder(String name) {
        Folder folder = folder(10L, PUBLIC_ID, name);
        when(folderRepository.findByPublicIdAndUser_UserId(PUBLIC_ID, USER_ID)).thenReturn(Optional.of(folder));
        return folder;
    }

    private static Folder folder(Long id, UUID publicId, String name) {
        return Folder.builder().folderId(id).publicId(publicId).folderName(name).createdAt(T0).build();
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
