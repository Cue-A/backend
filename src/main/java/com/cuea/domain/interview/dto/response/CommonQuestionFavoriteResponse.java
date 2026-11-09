package com.cuea.domain.interview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공통 질문 즐겨찾기 켜기 · 끄기 결과")
public record CommonQuestionFavoriteResponse(

        @Schema(description = "요청을 처리한 뒤의 상태")
        boolean favorite
) {
}
