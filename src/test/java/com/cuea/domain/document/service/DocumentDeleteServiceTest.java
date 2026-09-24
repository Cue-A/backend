package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.document.entity.FileFormat;
import com.cuea.domain.document.entity.SourceType;
import com.cuea.domain.interview.service.InterviewDocumentUsageService;
import com.cuea.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 문서 삭제 규칙을 DB · S3 없이 검증합니다.
 *
 * <p>핵심은 <b>S3 파일을 언제 지우는가</b>입니다. 면접에 쓰인 문서의 파일을 지우면
 * 재연습이 깨지므로, 어떤 키가 지워졌는지까지 봅니다.
 */
class DocumentDeleteServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID PUBLIC_ID = UUID.randomUUID();
    private static final String OBJECT_KEY = "resumes/user-1/" + PUBLIC_ID + ".pdf";

    private DocumentWriter documentWriter;
    private FakeDocumentFileStorage fileStorage;
    private InterviewDocumentUsageService documentUsageService;
    private DocumentDeleteService service;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.create("kim@example.com", "김취준");
        documentWriter = mock(DocumentWriter.class);
        fileStorage = new FakeDocumentFileStorage();
        documentUsageService = mock(InterviewDocumentUsageService.class);
        service = new DocumentDeleteService(documentWriter, fileStorage, documentUsageService);
    }

    @Test
    void 면접에_안_쓴_문서는_S3_파일까지_지운다() {
        when(documentWriter.markDeleted(PUBLIC_ID, USER_ID)).thenReturn(fileDocument(OBJECT_KEY));
        when(documentUsageService.isUsedInAnySession(anyLong())).thenReturn(false);

        service.delete(USER_ID, PUBLIC_ID.toString());

        assertThat(fileStorage.removed).containsExactly(OBJECT_KEY);
    }

    /** 재연습이 원래 면접의 문서 파일을 AI 에 다시 넘기므로 지우면 재연습이 깨집니다. */
    @Test
    void 면접에_쓰인_문서는_S3_파일을_남긴다() {
        when(documentWriter.markDeleted(PUBLIC_ID, USER_ID)).thenReturn(fileDocument(OBJECT_KEY));
        when(documentUsageService.isUsedInAnySession(anyLong())).thenReturn(true);

        service.delete(USER_ID, PUBLIC_ID.toString());

        verify(documentWriter).markDeleted(PUBLIC_ID, USER_ID);
        assertThat(fileStorage.removed).isEmpty();
    }

    /** S3 사본이 없는 예전 마크다운 문서는 숨기기만 하면 끝입니다. */
    @Test
    void S3_파일이_없는_문서는_숨기기만_한다() {
        when(documentWriter.markDeleted(PUBLIC_ID, USER_ID)).thenReturn(markdownWithoutCopy());

        service.delete(USER_ID, PUBLIC_ID.toString());

        verify(documentWriter).markDeleted(PUBLIC_ID, USER_ID);
        verify(documentUsageService, never()).isUsedInAnySession(any());
        assertThat(fileStorage.removed).isEmpty();
    }

    /** 없는 문서·남의 문서·이미 지운 문서는 조회 단계에서 구별 없이 404 입니다. */
    @Test
    void 찾을_수_없는_문서면_파일을_건드리지_않고_404() {
        when(documentWriter.markDeleted(any(UUID.class), anyString()))
                .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

        assertThatThrownBy(() -> service.delete(USER_ID, PUBLIC_ID.toString()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);

        assertThat(fileStorage.removed).isEmpty();
    }

    @Test
    void UUID_가_아닌_ID_는_없는_문서로_취급한다() {
        assertThatThrownBy(() -> service.delete(USER_ID, "not-a-uuid"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);

        verify(documentWriter, never()).markDeleted(any(), any());
    }

    private Document markdownWithoutCopy() {
        return Document.ofMarkdown(user, PUBLIC_ID, DocType.RESUME, "직접 쓴 자소서", "본문",
                DocumentStatus.READY);
    }

    private Document fileDocument(String objectKey) {
        return Document.builder()
                .docId(1L)
                .publicId(PUBLIC_ID)
                .user(user)
                .docTitle("자소서")
                .docType(DocType.RESUME)
                .sourceType(SourceType.FILE)
                .fileName("resume.pdf")
                .objectKey(objectKey)
                .fileFormat(FileFormat.PDF)
                .status(DocumentStatus.READY)
                .build();
    }
}
