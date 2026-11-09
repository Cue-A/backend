package com.cuea.domain.interview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/** 질문은행 목록. 연습 기록 목록과 같은 이유로 {@code Page} 를 그대로 내보내지 않습니다. */
@Schema(description = "질문은행 목록")
public record BookmarkListResponse(

        List<BookmarkResponse> bookmarks,

        @Schema(description = "0부터 시작")
        int page,

        int size,

        long totalElements,

        int totalPages
) {

    public static BookmarkListResponse from(Page<BookmarkResponse> page) {
        return new BookmarkListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
