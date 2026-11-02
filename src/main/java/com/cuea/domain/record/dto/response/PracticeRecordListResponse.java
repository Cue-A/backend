package com.cuea.domain.record.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/** 연습 기록 목록. 문서 목록과 같은 이유로 {@code Page} 를 그대로 내보내지 않습니다. */
@Schema(description = "연습 기록 목록")
public record PracticeRecordListResponse(

        List<PracticeRecordResponse> records,

        @Schema(description = "0부터 시작")
        int page,

        int size,

        @Schema(description = "필터를 적용한 전체 개수")
        long totalElements,

        int totalPages
) {

    public static PracticeRecordListResponse from(Page<PracticeRecordResponse> page) {
        return new PracticeRecordListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
