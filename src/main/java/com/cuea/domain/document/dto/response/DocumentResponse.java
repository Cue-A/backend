package com.cuea.domain.document.dto.response;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.IndexStatus;
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

        @Schema(description = "면접 시작 가능 여부. COMPLETED 여야 시작할 수 있습니다")
        IndexStatus indexStatus,

        OffsetDateTime createdAt
) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getPublicId().toString(),
                document.getDocType(),
                document.getSourceType(),
                document.getDocTitle(),
                document.getFileName(),
                document.getFileSize(),
                document.indexStatus(),
                document.getCreatedAt());
    }
}
