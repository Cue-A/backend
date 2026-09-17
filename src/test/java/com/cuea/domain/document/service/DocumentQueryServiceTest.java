package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.dto.response.DocumentDetailResponse;
import com.cuea.domain.document.dto.response.DocumentListResponse;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.document.entity.FileFormat;
import com.cuea.domain.document.entity.IndexStatus;
import com.cuea.domain.document.repository.DocumentRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
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
 * 조회 규칙을 DB · S3 없이 검증합니다.
 *
 * <p>가장 중요한 건 <b>남의 문서가 새지 않는가</b>입니다. 소유자 조건이 쿼리에
 * 실제로 실리는지까지 봅니다.
 */
class DocumentQueryServiceTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OBJECT_KEY = "resumes/user/doc.pdf";

    private DocumentRepository documentRepository;
    private PresignedUrlIssuer presignedUrlIssuer;
    private DocumentQueryService service;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.create("kim@example.com", "김취준");
        documentRepository = mock(DocumentRepository.class);
        presignedUrlIssuer = mock(PresignedUrlIssuer.class);
        when(presignedUrlIssuer.issueDocumentDownload(anyString())).thenReturn("https://s3/signed");
        service = new DocumentQueryService(documentRepository, presignedUrlIssuer);
    }

    // ── 목록 ────────────────────────────────────────────────

    @Test
    void 목록은_소유자로_좁혀_조회한다() {
        when(documentRepository.findByUser_UserId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(markdown("자소서"))));

        DocumentListResponse response = service.list(USER_ID, null, null, null);

        assertThat(response.documents()).hasSize(1);
        verify(documentRepository).findByUser_UserId(eq(USER_ID), any(Pageable.class));
    }

    @Test
    void documentType_을_주면_종류로도_좁힌다() {
        when(documentRepository.findByUser_UserIdAndDocType(eq(USER_ID), eq(DocType.PORTFOLIO), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.list(USER_ID, DocType.PORTFOLIO, null, null);

        verify(documentRepository).findByUser_UserIdAndDocType(eq(USER_ID), eq(DocType.PORTFOLIO), any(Pageable.class));
        verify(documentRepository, never()).findByUser_UserId(anyString(), any(Pageable.class));
    }

    /** 204 대신 200 입니다. 프론트가 두 가지 응답을 처리하지 않아도 되게. */
    @Test
    void 문서가_없어도_빈_목록을_돌려준다() {
        when(documentRepository.findByUser_UserId(anyString(), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, DocumentQueryService.DEFAULT_PAGE_SIZE)));

        DocumentListResponse response = service.list(USER_ID, null, null, null);

        assertThat(response.documents()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.page()).isZero();
    }

    @Test
    void 기본_페이지는_0_이고_크기는_20_이다() {
        when(documentRepository.findByUser_UserId(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        service.list(USER_ID, null, null, null);

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(DocumentQueryService.DEFAULT_PAGE_SIZE);
    }

    /** 상한이 없으면 size=100000 한 방으로 전부 긁어갈 수 있습니다. */
    @Test
    void size_는_상한을_넘지_못한다() {
        when(documentRepository.findByUser_UserId(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        service.list(USER_ID, null, 0, 100_000);

        assertThat(capturePageable().getPageSize()).isEqualTo(DocumentQueryService.MAX_PAGE_SIZE);
    }

    @Test
    void 음수_페이지와_0_이하_크기는_기본값으로_되돌린다() {
        when(documentRepository.findByUser_UserId(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        service.list(USER_ID, null, -5, 0);

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(DocumentQueryService.DEFAULT_PAGE_SIZE);
    }

    /** 방금 올린 자소서를 찾으러 뒤 페이지로 넘기게 하지 않습니다. */
    @Test
    void 목록은_최신순이다() {
        when(documentRepository.findByUser_UserId(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        service.list(USER_ID, null, null, null);

        Sort.Order order = capturePageable().getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    // ── 상세 ────────────────────────────────────────────────

    @Test
    void 마크다운_상세는_본문을_주고_다운로드_URL_은_없다() {
        Document document = markdown("## 지원 동기");
        when(documentRepository.findByPublicIdAndUser_UserId(any(UUID.class), eq(USER_ID)))
                .thenReturn(Optional.of(document));

        DocumentDetailResponse response = service.detail(USER_ID, document.getPublicId().toString());

        assertThat(response.content()).isEqualTo("## 지원 동기");
        assertThat(response.downloadUrl()).isNull();
    }

    @Test
    void 파일_상세는_다운로드_URL_을_주고_본문은_없다() {
        Document document = file();
        when(documentRepository.findByPublicIdAndUser_UserId(any(UUID.class), eq(USER_ID)))
                .thenReturn(Optional.of(document));

        DocumentDetailResponse response = service.detail(USER_ID, document.getPublicId().toString());

        assertThat(response.downloadUrl()).isEqualTo("https://s3/signed");
        assertThat(response.content()).isNull();
        verify(presignedUrlIssuer).issueDocumentDownload(OBJECT_KEY);
    }

    /**
     * 403 으로 돌려주면 "권한 없음"이 곧 "그 문서는 존재한다"가 됩니다.
     * 남의 문서와 없는 문서를 구별할 수 없어야 합니다.
     */
    @Test
    void 남의_문서는_없는_문서와_똑같이_404_다() {
        when(documentRepository.findByPublicIdAndUser_UserId(any(UUID.class), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(USER_ID, UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    /** UUID 가 아니어도 형식 오류로 구분해주지 않습니다. 구분해주면 그것도 정보입니다. */
    @Test
    void 잘못된_형식의_ID_도_404_다() {
        assertThatThrownBy(() -> service.detail(USER_ID, "not-a-uuid"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);

        verify(documentRepository, never()).findByPublicIdAndUser_UserId(any(UUID.class), anyString());
    }

    @Test
    void 인덱싱_필드는_아직_비어_있다() {
        Document document = markdown("본문");
        when(documentRepository.findByPublicIdAndUser_UserId(any(UUID.class), eq(USER_ID)))
                .thenReturn(Optional.of(document));

        DocumentDetailResponse response = service.detail(USER_ID, document.getPublicId().toString());

        assertThat(response.indexStatus()).isEqualTo(IndexStatus.COMPLETED);
        assertThat(response.indexedAt()).isNull();
        assertThat(response.indexError()).isNull();
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepository).findByUser_UserId(anyString(), captor.capture());
        return captor.getValue();
    }

    private Document markdown(String content) {
        return Document.ofMarkdown(user, UUID.randomUUID(), DocType.RESUME, "제목", content, DocumentStatus.READY);
    }

    private Document file() {
        return Document.ofFile(user, UUID.randomUUID(), DocType.RESUME, "제목", "resume.pdf",
                OBJECT_KEY, "application/pdf", 1_024, FileFormat.PDF, DocumentStatus.READY);
    }
}
