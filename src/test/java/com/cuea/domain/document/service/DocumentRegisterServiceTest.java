package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.dto.request.DocumentCreateCommand;
import com.cuea.domain.document.dto.response.DocumentResponse;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.IndexStatus;
import com.cuea.domain.document.entity.SourceType;
import com.cuea.domain.document.repository.DocumentRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import com.cuea.infrastructure.file.FileValidator;
import com.cuea.infrastructure.file.UploadedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 문서 등록 규칙을 DB · S3 없이 검증합니다.
 *
 * <p>{@link FileValidator} 만 진짜를 씁니다. 파일 검증은 순수 로직인데다,
 * 목으로 바꾸면 "확장자와 MIME 을 함께 본다"는 이 API 의 핵심 규칙이 테스트에서
 * 통째로 사라집니다.
 */
class DocumentRegisterServiceTest {

    private static final String PDF = "application/pdf";

    private DocumentRepository documentRepository;
    private FakeDocumentFileStorage fileStorage;
    private DocumentWriter documentWriter;
    private DocumentRegisterService service;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.create("kim@example.com", "김취준");

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(anyString())).thenReturn(Optional.of(user));

        documentRepository = mock(DocumentRepository.class);
        when(documentRepository.countByUser_UserId(anyString())).thenReturn(0L);

        // save 는 넘어온 엔티티를 그대로 돌려줍니다. ID 발급은 DB 몫이라
        // 여기서 흉내 내지 않습니다.
        documentWriter = mock(DocumentWriter.class);
        when(documentWriter.save(any(Document.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        fileStorage = new FakeDocumentFileStorage();

        service = new DocumentRegisterService(
                documentRepository, userRepository, fileStorage, new FileValidator(), documentWriter);
    }

    @Test
    void 파일_문서를_등록하면_올린_위치를_문서에_남긴다() {
        DocumentResponse response = service.register(user.getUserId(), fileCommand("resume.pdf", PDF, 1_024));

        assertThat(fileStorage.stored).hasSize(1);
        assertThat(response.sourceType()).isEqualTo(SourceType.FILE);
        assertThat(response.fileName()).isEqualTo("resume.pdf");
        assertThat(response.fileSize()).isEqualTo(1_024);
        assertThat(response.documentId()).isNotBlank();
    }

    /**
     * 인덱싱이 없으므로 등록 즉시 면접에 쓸 수 있습니다. AI 인덱싱이 붙으면
     * 이 테스트가 깨지고, 그때 PROCESSING 으로 바꾸는 게 맞습니다.
     */
    @Test
    void 인덱싱이_없으므로_등록_즉시_COMPLETED_다() {
        DocumentResponse response = service.register(user.getUserId(), fileCommand("resume.pdf", PDF, 1_024));

        assertThat(response.indexStatus()).isEqualTo(IndexStatus.COMPLETED);
    }

    @Test
    void 마크다운_문서는_파일_관련_필드가_비어_있다() {
        DocumentResponse response = service.register(user.getUserId(), markdownCommand("## 지원 동기\n저는 ..."));

        assertThat(response.sourceType()).isEqualTo(SourceType.MARKDOWN);
        assertThat(response.fileName()).isNull();
        assertThat(response.fileSize()).isNull();
        assertThat(fileStorage.stored).isEmpty();
    }

    /** documentType 은 목록 필터용이라 필수가 아닙니다. 안 보내면 RESUME 입니다. */
    @Test
    void documentType_을_안_보내면_RESUME_이_된다() {
        DocumentCreateCommand command = new DocumentCreateCommand(
                SourceType.MARKDOWN, null, "직접 쓴 자소서", "본문입니다", null);

        assertThat(command.documentType()).isEqualTo(DocType.RESUME);
    }

    @Test
    void FILE_인데_파일이_없으면_거절한다() {
        DocumentCreateCommand command = new DocumentCreateCommand(
                SourceType.FILE, DocType.RESUME, "제목", null, null);

        assertThatThrownBy(() -> service.register(user.getUserId(), command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_SOURCE_TYPE);
    }

    @Test
    void MARKDOWN_인데_본문이_비면_거절한다() {
        DocumentCreateCommand command = new DocumentCreateCommand(
                SourceType.MARKDOWN, DocType.RESUME, "제목", "   ", null);

        assertThatThrownBy(() -> service.register(user.getUserId(), command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_SOURCE_TYPE);
    }

    @Test
    void 문서가_상한이면_거절한다() {
        when(documentRepository.countByUser_UserId(anyString()))
                .thenReturn((long) DocumentRegisterService.MAX_DOCUMENTS_PER_USER);

        assertThatThrownBy(() -> service.register(user.getUserId(), fileCommand("resume.pdf", PDF, 1_024)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_LIMIT_EXCEEDED);
    }

    /**
     * 상한 검사가 업로드보다 뒤에 있으면 10MB 를 다 받아 올린 다음 거절하게
     * 됩니다. 거절당한 사용자의 파일이 버킷에 남는 것도 문제입니다.
     */
    @Test
    void 상한_초과는_파일을_올리기_전에_막는다() {
        when(documentRepository.countByUser_UserId(anyString()))
                .thenReturn((long) DocumentRegisterService.MAX_DOCUMENTS_PER_USER);

        assertThatThrownBy(() -> service.register(user.getUserId(), fileCommand("resume.pdf", PDF, 1_024)))
                .isInstanceOf(BusinessException.class);

        assertThat(fileStorage.stored).isEmpty();
    }

    @Test
    void 마크다운_본문이_상한을_넘으면_거절한다() {
        String tooLong = "가".repeat(DocumentRegisterService.MAX_MARKDOWN_LENGTH + 1);

        assertThatThrownBy(() -> service.register(user.getUserId(), markdownCommand(tooLong)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 제목이_상한을_넘으면_거절한다() {
        String tooLong = "가".repeat(DocumentRegisterService.MAX_TITLE_LENGTH + 1);
        DocumentCreateCommand command = new DocumentCreateCommand(
                SourceType.MARKDOWN, DocType.RESUME, tooLong, "본문", null);

        assertThatThrownBy(() -> service.register(user.getUserId(), command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    /**
     * 이 API 가 3단계를 1단계로 합친 이유 자체가 고아 객체를 없애려던 것이라,
     * 여기서 되돌리지 않으면 합친 의미가 없어집니다.
     */
    @Test
    void 저장에_실패하면_올린_파일을_지운다() {
        when(documentWriter.save(any(Document.class)))
                .thenThrow(new IllegalStateException("DB 끊김"));

        assertThatThrownBy(() -> service.register(user.getUserId(), fileCommand("resume.pdf", PDF, 1_024)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 끊김");

        assertThat(fileStorage.removed).isEqualTo(fileStorage.stored);
    }

    @Test
    void 마크다운은_저장에_실패해도_지울_파일이_없다() {
        when(documentWriter.save(any(Document.class)))
                .thenThrow(new IllegalStateException("DB 끊김"));

        assertThatThrownBy(() -> service.register(user.getUserId(), markdownCommand("본문")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fileStorage.removed).isEmpty();
    }

    @Test
    void 없는_사용자면_거절한다() {
        UserRepository empty = mock(UserRepository.class);
        when(empty.findById(anyString())).thenReturn(Optional.empty());
        DocumentRegisterService withoutUser = new DocumentRegisterService(
                documentRepository, empty, fileStorage, new FileValidator(), documentWriter);

        assertThatThrownBy(() -> withoutUser.register("없는사람", markdownCommand("본문")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
    }

    /**
     * 스트림을 컨트롤러에서 미리 열면, 검증에서 거부된 파일의 스트림이 읽히지도
     * 닫히지도 않은 채 버려집니다. {@code .hwp} 를 올릴 때마다 파일 디스크립터가
     * 하나씩 새는 셈이라, 거부 경로에서는 아예 열리지 않아야 합니다.
     */
    @Test
    void 검증에서_거부되면_파일_스트림을_열지_않는다() {
        AtomicInteger opened = new AtomicInteger();
        UploadedFile file = new UploadedFile("이력서.hwp", "application/haansofthwp", 1_024,
                () -> {
                    opened.incrementAndGet();
                    return new ByteArrayInputStream(new byte[]{1, 2, 3});
                });

        assertThatThrownBy(() -> service.register(user.getUserId(), new DocumentCreateCommand(
                SourceType.FILE, DocType.RESUME, "제목", null, file)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_FILE_TYPE);

        assertThat(opened).hasValue(0);
    }

    /** 정상 경로에서는 저장소가 한 번만 열어야 합니다. 두 번 열면 스트림이 하나 샙니다. */
    @Test
    void 정상_등록이면_스트림을_한_번만_연다() {
        AtomicInteger opened = new AtomicInteger();
        UploadedFile file = new UploadedFile("이력서.pdf", PDF, 1_024,
                () -> {
                    opened.incrementAndGet();
                    return new ByteArrayInputStream(new byte[]{1, 2, 3});
                });

        service.register(user.getUserId(), new DocumentCreateCommand(
                SourceType.FILE, DocType.RESUME, "제목", null, file));

        assertThat(opened).hasValue(1);
    }

    private DocumentCreateCommand fileCommand(String fileName, String contentType, long size) {
        UploadedFile file = new UploadedFile(
                fileName, contentType, size, () -> new ByteArrayInputStream(new byte[]{1, 2, 3}));
        return new DocumentCreateCommand(
                SourceType.FILE, DocType.RESUME, "2026 상반기 백엔드 자소서", null, file);
    }

    private DocumentCreateCommand markdownCommand(String content) {
        return new DocumentCreateCommand(
                SourceType.MARKDOWN, DocType.RESUME, "직접 쓴 자소서", content, null);
    }
}
