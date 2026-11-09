package com.cuea.domain.interview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "연습 기록 폴더 목록")
public record FolderListResponse(

        @Schema(description = "「전체」 카드의 세션 수. 연습 기록 목록을 필터 없이 불렀을 때의 totalElements 와 같습니다")
        long totalCount,

        @Schema(description = "만든 순서(오래된 것부터). 폴더가 없으면 빈 배열")
        List<FolderResponse> folders
) {
}
