package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.dto.response.DocumentResponse;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.document.entity.FileFormat;
import com.cuea.domain.document.entity.SourceType;
import com.cuea.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 문서 제목 수정 규칙을 DB 없이 검증합니다.
 *
 * <p>소유자·삭제 여부는 {@link DocumentWriter} 의 조회가 거르므로, 여기서는 그 결과가
 * 그대로 404 로 나가는지와 제목 규칙이 등록 때와 같은지를 봅니다.
 */
class DocumentUpdateServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID PUBLIC_ID = UUID.randomUUID();

    private DocumentWriter documentWriter;
    private DocumentUpdateService service;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.create("kim@example.com", "김취준");
        documentWriter = mock(DocumentWriter.class);
        service = new DocumentUpdateService(documentWriter);
    }

    @Test
    void 제목을_앞뒤_공백을_잘라_바꾼다() {
        Document document = markdownDocument();
        when(documentWriter.rename(PUBLIC_ID, USER_ID, "새 제목")).thenAnswer(invocation -> {
            document.rename(invocation.getArgument(2));
            return document;
        });

        DocumentResponse response = service.updateTitle(USER_ID, PUBLIC_ID.toString(), "  새 제목  ");

        verify(documentWriter).rename(PUBLIC_ID, USER_ID, "새 제목");
        assertThat(response.title()).isEqualTo("새 제목");
        assertThat(response.documentId()).isEqualTo(PUBLIC_ID.toString());
    }

    /** 본문을 못 바꾸는 파일 문서도 제목은 바꿀 수 있습니다. */
    @Test
    void 파일_문서도_제목은_바꾼다() {
        Document document = fileDocument();
        when(documentWriter.rename(PUBLIC_ID, USER_ID, "새 제목")).thenAnswer(invocation -> {
            document.rename(invocation.getArgument(2));
            return document;
        });

        DocumentResponse response = service.updateTitle(USER_ID, PUBLIC_ID.toString(), "새 제목");

        assertThat(response.title()).isEqualTo("새 제목");
        assertThat(response.sourceType()).isEqualTo(SourceType.FILE);
        assertThat(response.fileName()).isEqualTo("resume.pdf");
    }

    @Test
    void 빈_제목은_거절한다() {
        assertThatThrownBy(() -> service.updateTitle(USER_ID, PUBLIC_ID.toString(), "   "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);

        verify(documentWriter, never()).rename(any(), any(), any());
    }

    @Test
    void 제목이_없으면_거절한다() {
        assertThatThrownBy(() -> service.updateTitle(USER_ID, PUBLIC_ID.toString(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);

        verify(documentWriter, never()).rename(any(), any(), any());
    }

    @Test
    void 제목이_상한을_넘으면_거절한다() {
        String tooLong = "가".repeat(DocumentTitle.MAX_LENGTH + 1);

        assertThatThrownBy(() -> service.updateTitle(USER_ID, PUBLIC_ID.toString(), tooLong))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);

        verify(documentWriter, never()).rename(any(), any(), any());
    }

    /** 길이는 공백을 자른 뒤 셉니다. 등록 때와 같은 규칙입니다. */
    @Test
    void 공백을_자르면_상한_안이면_통과한다() {
        String title = "가".repeat(DocumentTitle.MAX_LENGTH);
        when(documentWriter.rename(PUBLIC_ID, USER_ID, title)).thenReturn(markdownDocument());

        service.updateTitle(USER_ID, PUBLIC_ID.toString(), " " + title + " ");

        verify(documentWriter).rename(PUBLIC_ID, USER_ID, title);
    }

    /** 없는 문서·남의 문서·삭제된 문서는 조회 단계에서 구별 없이 404 입니다. */
    @Test
    void 찾을_수_없는_문서면_404() {
        when(documentWriter.rename(any(UUID.class), anyString(), eq("새 제목")))
                .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

        assertThatThrownBy(() -> service.updateTitle(USER_ID, PUBLIC_ID.toString(), "새 제목"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    void UUID_가_아닌_ID_는_없는_문서로_취급한다() {
        assertThatThrownBy(() -> service.updateTitle(USER_ID, "not-a-uuid", "새 제목"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);

        verify(documentWriter, never()).rename(any(), any(), any());
    }

    private Document markdownDocument() {
        return Document.builder()
                .docId(1L)
                .publicId(PUBLIC_ID)
                .user(user)
                .docTitle("직접 쓴 자소서")
                .docType(DocType.RESUME)
                .sourceType(SourceType.MARKDOWN)
                .docText("본문")
                .status(DocumentStatus.READY)
                .build();
    }

    private Document fileDocument() {
        return Document.builder()
                .docId(1L)
                .publicId(PUBLIC_ID)
                .user(user)
                .docTitle("자소서")
                .docType(DocType.RESUME)
                .sourceType(SourceType.FILE)
                .fileName("resume.pdf")
                .objectKey("resumes/user-1/" + PUBLIC_ID + ".pdf")
                .fileFormat(FileFormat.PDF)
                .status(DocumentStatus.READY)
                .build();
    }
}
