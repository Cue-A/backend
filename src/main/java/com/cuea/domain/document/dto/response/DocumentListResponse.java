package com.cuea.domain.document.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 문서 목록.
 *
 * <p>{@code Page} 를 그대로 내보내지 않습니다. Spring 의 직렬화 형태는 버전에 따라
 * 바뀌고(`pageable`·`sort` 같은 내부 구조가 그대로 노출됩니다) 우리가 통제하지
 * 못합니다. 프론트가 쓰는 네 값만 추려서 우리 형태로 고정합니다.
 */
@Schema(description = "문서 목록")
public record DocumentListResponse(

        List<DocumentResponse> documents,

        @Schema(description = "0부터 시작")
        int page,

        int size,

        @Schema(description = "필터를 적용한 전체 개수")
        long totalElements,

        int totalPages
) {

    public static DocumentListResponse from(Page<DocumentResponse> page) {
        return new DocumentListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
