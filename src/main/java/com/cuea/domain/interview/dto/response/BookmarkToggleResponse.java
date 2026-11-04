package com.cuea.domain.interview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "북마크 켜기 · 끄기 결과")
public record BookmarkToggleResponse(

        @Schema(description = "요청을 처리한 뒤의 상태")
        boolean bookmarked
) {
}
