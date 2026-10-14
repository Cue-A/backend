package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.dto.response.DocumentDetailResponse;
import com.cuea.domain.document.dto.response.DocumentListResponse;
import com.cuea.domain.document.dto.response.DocumentResponse;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.SourceType;
import com.cuea.domain.document.repository.DocumentRepository;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 문서 조회. <b>본인 문서만 보입니다.</b>
 *
 * <h2>남의 문서는 404 입니다</h2>
 * 403 으로 돌려주면 "권한이 없다"는 말이 곧 <b>그 문서가 존재한다</b>는 뜻이
 * 됩니다. ID 를 바꿔가며 넣어보면 남의 문서 존재 여부를 알아낼 수 있습니다.
 * 없는 문서와 남의 문서를 구별할 수 없게 둘 다 {@code DOCUMENT_NOT_FOUND} 입니다.
 *
 * <p>소유자 조건을 쿼리에 묶어둔 것도 같은 이유입니다. 조회해놓고 서비스에서
 * 비교하는 방식은 비교를 한 번 빠뜨리면 그대로 열립니다.
 */
@Service
@RequiredArgsConstructor
public class DocumentQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 한 번에 가져갈 수 있는 최대 개수.
     *
     * <p>상한이 없으면 {@code size=100000} 한 방으로 전부 긁어갈 수 있습니다.
     * 문서가 사용자당 20개라 지금은 여유가 있지만, 상한은 지금 두는 게 쌉니다.
     */
    static final int MAX_PAGE_SIZE = 100;

    /** 최신 문서를 먼저 보여줍니다. 방금 올린 자소서를 찾으러 뒤로 넘기게 하지 않습니다. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final DocumentRepository documentRepository;
    private final PresignedUrlIssuer presignedUrlIssuer;

    @Transactional(readOnly = true)
    public DocumentListResponse list(String userId, DocType documentType, Integer page, Integer size) {
        Pageable pageable = PageRequest.of(pageOf(page), sizeOf(size), NEWEST_FIRST);

        Page<Document> documents = documentType == null
                ? documentRepository.findByUser_UserId(userId, pageable)
                : documentRepository.findByUser_UserIdAndDocType(userId, documentType, pageable);

        return DocumentListResponse.from(documents.map(DocumentResponse::from));
    }

    /**
     * {@code FILE} 이면 다운로드 URL 을 함께 만들어 돌려줍니다.
     *
     * <p>URL 발급은 서명 계산이라 네트워크를 타지 않습니다. 트랜잭션 안에 있어도
     * 커넥션을 오래 붙들지 않습니다.
     */
    @Transactional(readOnly = true)
    public DocumentDetailResponse detail(String userId, String documentId) {
        Document document = documentRepository
                .findByPublicIdAndUser_UserId(parsePublicId(documentId), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

        return DocumentDetailResponse.of(document, downloadUrlOf(document));
    }

    private String downloadUrlOf(Document document) {
        if (document.getSourceType() != SourceType.FILE || document.getObjectKey() == null) {
            return null;
        }
        return presignedUrlIssuer.issueDocumentDownload(document.getObjectKey());
    }

    /**
     * UUID 가 아닌 값은 <b>형식 오류가 아니라 없는 문서로</b> 취급합니다.
     *
     * <p>형식 오류로 구분해 내보내면 "UUID 형태면 존재할 수도 있다"는 정보를 주는
     * 셈입니다. 어차피 사용자가 할 일은 같습니다.
     */
    private UUID parsePublicId(String documentId) {
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
    }

    private int pageOf(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    private int sizeOf(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
