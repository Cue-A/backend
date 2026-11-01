package com.cuea.domain.document.dto.response;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.SourceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 문서 한 건의 응답.
 *
 * <p>{@code docId}(BIGINT PK)가 아니라 {@code publicId} 를 내보냅니다.
 * 연속된 정수를 노출하면 남의 문서 개수와 순서가 드러납니다.
 *
 * <p>{@code MARKDOWN} 문서는 {@code fileName}·{@code fileSize} 가 null 입니다.
 * <b>키를 빼지 않고 null 로 내보냅니다.</b> 프론트가 "파일이 없는 문서"와
 * "필드를 못 받은 상태"를 구분해야 하기 때문입니다.
 */
@Schema(description = "문서")
public record DocumentResponse(

        @Schema(description = "문서 식별자", example = "3f1a...")
        String documentId,

        DocType documentType,

        SourceType sourceType,

        String title,

        @Schema(description = "원본 파일명. MARKDOWN 이면 null")
        String fileName,

        @Schema(description = "바이트. MARKDOWN 이면 null")
        Long fileSize,

        OffsetDateTime createdAt,

        @Schema(description = "마지막 수정 시각. 수정한 적 없으면 등록 시점입니다(createdAt 과 미세하게 다를 수 있음)")
        OffsetDateTime updatedAt
) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getPublicId().toString(),
                document.getDocType(),
                document.getSourceType(),
                document.getDocTitle(),
                document.getFileName(),
                document.getFileSize(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
