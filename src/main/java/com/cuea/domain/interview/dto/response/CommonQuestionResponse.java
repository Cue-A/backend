package com.cuea.domain.interview.dto.response;

import com.cuea.domain.interview.entity.CommonQuestion;
import com.cuea.domain.interview.entity.CommonQuestionNote;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "공통 질문 한 개와 그 질문에 대한 내 즐겨찾기 · 메모")
public record CommonQuestionResponse(

        @Schema(description = "즐겨찾기 · 메모 · 수정 · 삭제에 씁니다")
        String questionId,

        String text,

        @Schema(description = "인성 | 경험 | 지원동기 | 직무 | 기타")
        String category,

        boolean favorite,

        @Schema(description = "즐겨찾기를 켠 시각. 꺼져 있으면 null")
        OffsetDateTime favoritedAt,

        @Schema(description = "쓰지 않았으면 null")
        String memo,

        @Schema(description = "내가 직접 추가한 질문이면 true. true 인 질문만 고치고 지울 수 있습니다")
        boolean custom
) {

    /** @param note 즐겨찾기 · 메모를 한 번도 하지 않았으면 null */
    public static CommonQuestionResponse of(CommonQuestion question, CommonQuestionNote note) {
        return new CommonQuestionResponse(
                question.getPublicId().toString(),
                question.getText(),
                question.getCategory(),
                note != null && note.isFavorite(),
                note == null ? null : note.getFavoritedAt(),
                note == null ? null : note.getMemo(),
                question.isCustom());
    }
}
