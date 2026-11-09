package com.cuea.domain.interview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "공통 질문 목록")
public record CommonQuestionListResponse(

        @Schema(description = "내 질문(최근에 추가한 것부터) 다음에 기본 질문(정해진 순서). 즐겨찾기 정렬은 하지 않습니다")
        List<CommonQuestionResponse> questions
) {
}
