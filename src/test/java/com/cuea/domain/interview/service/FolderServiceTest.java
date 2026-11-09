package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.FolderListResponse;
import com.cuea.domain.interview.dto.response.FolderMoveResponse;
import com.cuea.domain.interview.dto.response.FolderResponse;
import com.cuea.domain.interview.entity.Folder;
import com.cuea.domain.interview.entity.InterviewSession;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    /** 2회차만 보내도 1회차와 다른 재연습이 같은 폴더로 갑니다. */
    @Test
    void 보낸_회차가_속한_면접의_회차를_모두_옮긴다() {
        Folder folder = givenFolder("삼성전자");
        InterviewSession root = session("root", null);
        InterviewSession second = session("second", root);
        InterviewSession third = session("third", root);
        when(sessionRepository.findAllBySessionIdInAndUser_UserIdAndStatusIn(
                Set.of("second"), USER_ID, SessionStatus.LISTED)).thenReturn(List.of(second));
        when(sessionRepository.findInterviewsOf(USER_ID, Set.of("root"), SessionStatus.LISTED))
                .thenReturn(List.of(root, second, third));

        FolderMoveResponse response = service.moveSessions(USER_ID, List.of("second"), PUBLIC_ID);

        assertThat(response.folderId()).isEqualTo(PUBLIC_ID.toString());
        assertThat(response.movedSessionIds()).containsExactly("root", "second", "third");
        assertThat(List.of(root, second, third)).allSatisfy(s -> assertThat(s.getFolder()).isSameAs(folder));
    }

    @Test
    void folderId_가_null_이면_폴더에서_뺀다() {
        InterviewSession root = session("root", null);
        root.moveTo(folder(1L, UUID.randomUUID(), "카카오"));
        when(sessionRepository.findAllBySessionIdInAndUser_UserIdAndStatusIn(
                Set.of("root"), USER_ID, SessionStatus.LISTED)).thenReturn(List.of(root));
        when(sessionRepository.findInterviewsOf(USER_ID, Set.of("root"), SessionStatus.LISTED))
                .thenReturn(List.of(root));

        FolderMoveResponse response = service.moveSessions(USER_ID, List.of("root"), null);

        assertThat(response.folderId()).isNull();
        assertThat(root.getFolder()).isNull();
        verify(folderRepository, never()).findByPublicIdAndUser_UserId(any(), anyString());
    }

    /** 하나라도 못 찾으면 아무것도 옮기지 않습니다. 남의 세션 · 중단된 세션도 조회에서 빠져 여기로 옵니다. */
    @Test
    void 보낸_세션을_하나라도_못_찾으면_SESSION_NOT_FOUND() {
        givenFolder("삼성전자");
        when(sessionRepository.findAllBySessionIdInAndUser_UserIdAndStatusIn(
                Set.of("mine", "others"), USER_ID, SessionStatus.LISTED)).thenReturn(List.of(session("mine", null)));

        assertErrorCode(() -> service.moveSessions(USER_ID, List.of("mine", "others"), PUBLIC_ID),
                ErrorCode.SESSION_NOT_FOUND);
        verify(sessionRepository, never()).findInterviewsOf(anyString(), anyCollection(), any());
    }

    @Test
    void 폴더가_없거나_남의_것이면_FOLDER_NOT_FOUND() {
        when(folderRepository.findByPublicIdAndUser_UserId(PUBLIC_ID, USER_ID)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.moveSessions(USER_ID, List.of("root"), PUBLIC_ID), ErrorCode.FOLDER_NOT_FOUND);
    }

    /** 중복을 빼고 셉니다. 같은 ID 를 두 번 보내도 못 찾은 것으로 보지 않습니다. */
    @Test
    void 같은_세션을_두_번_보내도_한_번으로_본다() {
        givenFolder("삼성전자");
        InterviewSession root = session("root", null);
        when(sessionRepository.findAllBySessionIdInAndUser_UserIdAndStatusIn(
                Set.of("root"), USER_ID, SessionStatus.LISTED)).thenReturn(List.of(root));
        when(sessionRepository.findInterviewsOf(eq(USER_ID), anyCollection(), any())).thenReturn(List.of(root));

        assertThat(service.moveSessions(USER_ID, List.of("root", "root"), PUBLIC_ID).movedSessionIds())
                .containsExactly("root");
    }

    @Test
    void sessionIds_가_비었거나_100개를_넘으면_INVALID_REQUEST() {
        List<String> tooMany = IntStream.rangeClosed(1, 101).mapToObj(i -> "sess_" + i).toList();

        assertErrorCode(() -> service.moveSessions(USER_ID, Collections.emptyList(), PUBLIC_ID), ErrorCode.INVALID_REQUEST);
        assertErrorCode(() -> service.moveSessions(USER_ID, null, PUBLIC_ID), ErrorCode.INVALID_REQUEST);
        assertErrorCode(() -> service.moveSessions(USER_ID, tooMany, PUBLIC_ID), ErrorCode.INVALID_REQUEST);
    }

    private static InterviewSession session(String id, InterviewSession root) {
        return InterviewSession.builder().sessionId(id).retryOfSession(root)
                .status(SessionStatus.COMPLETED).createdAt(T0).build();
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
