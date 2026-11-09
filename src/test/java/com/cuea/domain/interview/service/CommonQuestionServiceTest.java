package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.CommonQuestionFavoriteResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionListResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionMemoResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionResponse;
import com.cuea.domain.interview.entity.CommonQuestion;
import com.cuea.domain.interview.entity.CommonQuestionNote;
import com.cuea.domain.interview.repository.CommonQuestionNoteRepository;
import com.cuea.domain.interview.repository.CommonQuestionRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 공통 질문의 보이는 범위 · 수정 권한 · 개수 상한 · 즐겨찾기 시각 규칙을 DB 없이 봅니다. 쿼리는 CommonQuestionRepositoryTest 몫입니다. */
class CommonQuestionServiceTest {

    private static final String USER_ID = "user-1";
    private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-01T00:00:00Z");

    private CommonQuestionRepository questionRepository;
    private CommonQuestionNoteRepository noteRepository;
    private UserRepository userRepository;
    private CommonQuestionService service;

    private User owner;

    @BeforeEach
    void setUp() {
        questionRepository = mock(CommonQuestionRepository.class);
        noteRepository = mock(CommonQuestionNoteRepository.class);
        userRepository = mock(UserRepository.class);
        service = new CommonQuestionService(questionRepository, noteRepository, userRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        owner = User.create(null, "면접자");
    }

    // ── 목록 ─────────────────────────────────────────────

    @Test
    void 목록은_내_질문_다음에_기본_질문이고_질문마다_내_노트를_붙인다() {
        CommonQuestion newer = custom(11L, "나중에 추가");
        CommonQuestion older = custom(10L, "먼저 추가");
        CommonQuestion intro = defaultQuestion(1L, "1분 자기소개를 해주세요.");
        CommonQuestion strength = defaultQuestion(2L, "본인의 장점과 보완할 노력을 말해주세요.");
        when(questionRepository.findAllByUser_UserIdOrderByCreatedAtDescCommonQuestionIdDesc(USER_ID))
                .thenReturn(List.of(newer, older));
        when(questionRepository.findAllDefaults()).thenReturn(List.of(intro, strength));
        when(noteRepository.findAllOwned(USER_ID)).thenReturn(List.of(
                note(strength, true, T0, null),
                note(older, false, null, "STAR 로 정리")));

        CommonQuestionListResponse response = service.list(USER_ID);

        assertThat(response.questions())
                .extracting(CommonQuestionResponse::text, CommonQuestionResponse::custom,
                        CommonQuestionResponse::favorite, CommonQuestionResponse::favoritedAt,
                        CommonQuestionResponse::memo)
                .containsExactly(
                        tuple("나중에 추가", true, false, null, null),
                        tuple("먼저 추가", true, false, null, "STAR 로 정리"),
                        tuple("1분 자기소개를 해주세요.", false, false, null, null),
                        tuple("본인의 장점과 보완할 노력을 말해주세요.", false, true, T0, null));
        assertThat(response.questions().getFirst().questionId()).isEqualTo(newer.getPublicId().toString());
    }

    // ── 즐겨찾기 ───────────────────────────────────────────

    @Test
    void 즐겨찾기를_켜면_노트를_만들고_지금_시각을_찍는다() {
        CommonQuestion question = givenVisible(defaultQuestion(1L, "1분 자기소개를 해주세요."));
        CommonQuestionNote note = givenNote(question, note(question, false, null, null));

        CommonQuestionFavoriteResponse response =
                service.setFavorite(USER_ID, question.getPublicId().toString(), true);

        assertThat(response.favorite()).isTrue();
        assertThat(note.isFavorite()).isTrue();
        assertThat(note.getFavoritedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(noteRepository).insertIfAbsent(USER_ID, 1L);
    }

    /** 이미 켜진 질문을 다시 켜도 처음 켠 시각이 남아야 즐겨찾기 순서가 흔들리지 않습니다. */
    @Test
    void 이미_켜진_즐겨찾기를_다시_켜면_처음_켠_시각이_남는다() {
        CommonQuestion question = givenVisible(defaultQuestion(1L, "1분 자기소개를 해주세요."));
        CommonQuestionNote note = givenNote(question, note(question, true, T0, null));

        assertThat(service.setFavorite(USER_ID, question.getPublicId().toString(), true).favorite()).isTrue();

        assertThat(note.getFavoritedAt()).isEqualTo(T0);
    }

    @Test
    void 즐겨찾기를_끄면_시각은_지우고_메모는_남긴다() {
        CommonQuestion question = givenVisible(defaultQuestion(1L, "1분 자기소개를 해주세요."));
        CommonQuestionNote note = givenNote(question, note(question, true, T0, "두괄식으로"));

        assertThat(service.setFavorite(USER_ID, question.getPublicId().toString(), false).favorite()).isFalse();

        assertThat(note.isFavorite()).isFalse();
        assertThat(note.getFavoritedAt()).isNull();
        assertThat(note.getMemo()).isEqualTo("두괄식으로");
    }

    @Test
    void 보이지_않는_질문은_즐겨찾기가_404_이고_노트를_만들지_않는다() {
        UUID unknown = UUID.randomUUID();
        when(questionRepository.findVisible(unknown, USER_ID)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.setFavorite(USER_ID, unknown.toString(), true), ErrorCode.QUESTION_NOT_FOUND);
        verify(noteRepository, never()).insertIfAbsent(anyString(), anyLong());
    }

    /** 프론트 목업 ID(mock-common-01)처럼 UUID 가 아닌 값은 찾지도 않고 404 입니다. */
    @Test
    void UUID_가_아닌_질문_ID_는_404_이다() {
        assertErrorCode(() -> service.setFavorite(USER_ID, "mock-common-01", true), ErrorCode.QUESTION_NOT_FOUND);
        assertErrorCode(() -> service.update(USER_ID, "mock-custom-1", "문구", "기타"), ErrorCode.QUESTION_NOT_FOUND);
        assertErrorCode(() -> service.delete(USER_ID, "1"), ErrorCode.QUESTION_NOT_FOUND);
        verify(questionRepository, never()).findVisible(any(), anyString());
    }

    // ── 메모 ─────────────────────────────────────────────

    @Test
    void 메모를_저장하고_저장한_값을_돌려준다() {
        CommonQuestion question = givenVisible(custom(10L, "내 질문"));
        CommonQuestionNote note = givenNote(question, note(question, false, null, null));

        CommonQuestionMemoResponse response = service.editMemo(USER_ID, question.getPublicId().toString(), " 두괄식으로 ");

        assertThat(response.memo()).isEqualTo(" 두괄식으로 ");
        assertThat(note.getMemo()).isEqualTo(" 두괄식으로 ");
        assertThat(note.isFavorite()).isFalse();
    }

    @Test
    void 공백뿐인_메모는_메모를_지운다() {
        CommonQuestion question = givenVisible(defaultQuestion(1L, "1분 자기소개를 해주세요."));
        CommonQuestionNote note = givenNote(question, note(question, true, T0, "지울 메모"));

        assertThat(service.editMemo(USER_ID, question.getPublicId().toString(), "   ").memo()).isNull();

        assertThat(note.getMemo()).isNull();
        assertThat(note.isFavorite()).isTrue();
    }

    @Test
    void 메모가_500자를_넘으면_400_이고_질문을_찾지_않는다() {
        assertErrorCode(() -> service.editMemo(USER_ID, UUID.randomUUID().toString(), "가".repeat(501)),
                ErrorCode.INVALID_REQUEST);
        verify(questionRepository, never()).findVisible(any(), anyString());
    }

    // ── 추가 ─────────────────────────────────────────────

    @Test
    void 내_질문을_추가하면_자른_문구로_저장하고_custom_으로_돌려준다() {
        when(questionRepository.countByUser_UserId(USER_ID)).thenReturn(99L);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        when(questionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CommonQuestionResponse response = service.create(USER_ID, "  지원 동기를 말해 주세요.  ", "지원동기");

        ArgumentCaptor<CommonQuestion> saved = ArgumentCaptor.forClass(CommonQuestion.class);
        verify(questionRepository).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(owner);
        assertThat(saved.getValue().getText()).isEqualTo("지원 동기를 말해 주세요.");
        assertThat(saved.getValue().getPublicId()).isNotNull();
        assertThat(response).isEqualTo(new CommonQuestionResponse(saved.getValue().getPublicId().toString(),
                "지원 동기를 말해 주세요.", "지원동기", false, null, null, true));
    }

    @Test
    void 내_질문이_100개면_409_이고_저장하지_않는다() {
        when(questionRepository.countByUser_UserId(USER_ID)).thenReturn(100L);

        assertErrorCode(() -> service.create(USER_ID, "문구", "기타"), ErrorCode.CUSTOM_QUESTION_LIMIT_EXCEEDED);
        verify(questionRepository, never()).save(any());
    }

    @Test
    void 잘못된_문구나_분류는_400_이고_저장하지_않는다() {
        assertErrorCode(() -> service.create(USER_ID, "  ", "기타"), ErrorCode.INVALID_REQUEST);
        assertErrorCode(() -> service.create(USER_ID, "문구", "직무역량"), ErrorCode.INVALID_REQUEST);
        assertErrorCode(() -> service.create(USER_ID, "문구", null), ErrorCode.INVALID_REQUEST);
        verify(questionRepository, never()).save(any());
    }

    @Test
    void 사용자가_없으면_404_이다() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.create(USER_ID, "문구", "기타"), ErrorCode.USER_NOT_FOUND);
        verify(questionRepository, never()).save(any());
    }

    // ── 수정 ─────────────────────────────────────────────

    @Test
    void 내_질문을_고치면_자른_문구와_분류가_바뀌고_내_노트가_붙어_나온다() {
        CommonQuestion question = givenVisible(custom(10L, "고치기 전"));
        when(noteRepository.findOwned(USER_ID, 10L)).thenReturn(Optional.of(note(question, true, T0, "메모")));

        CommonQuestionResponse response =
                service.update(USER_ID, question.getPublicId().toString(), " 고친 뒤 ", "직무");

        assertThat(question.getText()).isEqualTo("고친 뒤");
        assertThat(question.getCategory()).isEqualTo("직무");
        assertThat(response).isEqualTo(new CommonQuestionResponse(question.getPublicId().toString(),
                "고친 뒤", "직무", true, T0, "메모", true));
    }

    @Test
    void 기본_질문은_고칠_수_없어_403_이다() {
        CommonQuestion question = givenVisible(defaultQuestion(1L, "1분 자기소개를 해주세요."));

        assertErrorCode(() -> service.update(USER_ID, question.getPublicId().toString(), "바꾼 문구", "기타"),
                ErrorCode.FORBIDDEN);
        assertThat(question.getText()).isEqualTo("1분 자기소개를 해주세요.");
    }

    @Test
    void 보이지_않는_질문을_고치면_404_이다() {
        UUID unknown = UUID.randomUUID();
        when(questionRepository.findVisible(unknown, USER_ID)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.update(USER_ID, unknown.toString(), "문구", "기타"), ErrorCode.QUESTION_NOT_FOUND);
    }

    // ── 삭제 ─────────────────────────────────────────────

    @Test
    void 내_질문을_지운다() {
        CommonQuestion question = givenVisible(custom(10L, "지울 질문"));

        service.delete(USER_ID, question.getPublicId().toString());

        verify(questionRepository).delete(question);
    }

    @Test
    void 기본_질문은_지울_수_없어_403_이다() {
        CommonQuestion question = givenVisible(defaultQuestion(1L, "1분 자기소개를 해주세요."));

        assertErrorCode(() -> service.delete(USER_ID, question.getPublicId().toString()), ErrorCode.FORBIDDEN);
        verify(questionRepository, never()).delete(any());
    }

    @Test
    void 보이지_않는_질문을_지우면_404_이다() {
        UUID unknown = UUID.randomUUID();
        when(questionRepository.findVisible(unknown, USER_ID)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.delete(USER_ID, unknown.toString()), ErrorCode.QUESTION_NOT_FOUND);
        verify(questionRepository, never()).delete(any());
    }

    // ── 도우미 ───────────────────────────────────────────

    private CommonQuestion givenVisible(CommonQuestion question) {
        when(questionRepository.findVisible(question.getPublicId(), USER_ID)).thenReturn(Optional.of(question));
        return question;
    }

    private CommonQuestionNote givenNote(CommonQuestion question, CommonQuestionNote note) {
        when(noteRepository.findOwned(USER_ID, question.getCommonQuestionId())).thenReturn(Optional.of(note));
        return note;
    }

    private CommonQuestion custom(long id, String text) {
        return question(id, owner, text);
    }

    private static CommonQuestion defaultQuestion(long id, String text) {
        return question(id, null, text);
    }

    private static CommonQuestion question(long id, User user, String text) {
        return CommonQuestion.builder()
                .commonQuestionId(id).publicId(UUID.randomUUID()).user(user).text(text).category("기타")
                .build();
    }

    private CommonQuestionNote note(CommonQuestion question, boolean favorite, OffsetDateTime favoritedAt, String memo) {
        return CommonQuestionNote.builder()
                .user(owner).commonQuestion(question).favorite(favorite).favoritedAt(favoritedAt).memo(memo)
                .build();
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
