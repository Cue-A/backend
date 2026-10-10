package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.dto.response.DocumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 문서 수정. <b>지금은 제목만 바꿉니다.</b>
 *
 * <p>파일 문서든 마크다운 문서든 제목은 똑같이 바뀝니다. 본문 수정은 DB 원본과
 * AI 가 읽는 S3 사본을 함께 바꿔야 해서 별도 작업입니다(Issue #75).
 *
 * <p>DB 쓰기는 다른 문서 서비스와 같이 {@link DocumentWriter} 로 넘깁니다. 본문
 * 수정이 붙으면 S3 업로드를 트랜잭션 밖에 둬야 하므로 지금부터 그 모양을 맞춰 둡니다.
 */
@Service
@RequiredArgsConstructor
public class DocumentUpdateService {

    private final DocumentWriter documentWriter;

    public DocumentResponse updateTitle(String userId, String documentId, String title) {
        UUID publicId = parsePublicId(documentId);
        DocumentTitle.validate(title);

        return DocumentResponse.from(documentWriter.rename(publicId, userId, title.trim()));
    }

    /** UUID 가 아닌 값도 없는 문서로 취급합니다. {@code DocumentQueryService} 와 같은 이유입니다. */
    private UUID parsePublicId(String documentId) {
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
    }
}
