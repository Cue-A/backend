package com.cuea.domain.document.dto.response;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.IndexStatus;
import com.cuea.domain.document.entity.SourceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 문서 상세.
 *
 * <p>{@code sourceType} 에 따라 본문을 얻는 방법이 갈립니다. {@code MARKDOWN} 은
 * {@code content} 에 본문이 그대로 실리고, {@code FILE} 은 {@code downloadUrl} 로
 * 받아갑니다. <b>둘 중 하나는 항상 null 입니다.</b>
 *
 * <p>{@code downloadUrl} 은 매 조회마다 새로 만듭니다. 만료되는 값이라 DB 에
 * 저장하지 않습니다. docs/20-storage.md 참고.
 */
@Schema(description = "문서 상세")
public record DocumentDetailResponse(

        String documentId,

        DocType documentType,

        SourceType sourceType,

        String title,

        @Schema(description = "MARKDOWN 본문. FILE 이면 null")
        String content,

        @Schema(description = "Presigned GET. FILE 일 때만 채워지며 1시간 뒤 만료됩니다")
        String downloadUrl,

        IndexStatus indexStatus,

        @Schema(description = "인덱싱 완료 시각. 인덱싱 도입 전이라 항상 null")
        OffsetDateTime indexedAt,

        @Schema(description = "인덱싱 실패 사유. 인덱싱 도입 전이라 항상 null")
        String indexError,

        OffsetDateTime createdAt,

        OffsetDateTime updatedAt
) {

    /**
     * @param downloadUrl {@code FILE} 이면 발급한 Presigned URL, {@code MARKDOWN} 이면 null
     */
    public static DocumentDetailResponse of(Document document, String downloadUrl) {
        boolean isFile = document.getSourceType() == SourceType.FILE;

        return new DocumentDetailResponse(
                document.getPublicId().toString(),
                document.getDocType(),
                document.getSourceType(),
                document.getDocTitle(),
                isFile ? null : document.getDocText(),
                downloadUrl,
                document.indexStatus(),
                // 인덱싱이 없어 채울 값이 없습니다. 응답 모양만 미리 맞춰둡니다.
                null,
                null,
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
