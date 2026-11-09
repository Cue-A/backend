package com.cuea.domain.interview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공통 질문 메모 저장 결과")
public record CommonQuestionMemoResponse(

        @Schema(description = "저장된 메모. 지웠으면 null")
        String memo
) {
}
