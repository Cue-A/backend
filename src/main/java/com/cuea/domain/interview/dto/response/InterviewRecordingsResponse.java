package com.cuea.domain.interview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * GET /api/interviews/{sessionId}/recordings 응답. 면접 다시보기 재생목록입니다.
 *
 * <p>면접 전체를 담은 영상 파일은 없습니다. 답변 영상이 문항마다 나뉘어 있어 프론트가
 * {@code items} 순서대로 이어서 재생합니다. URL 은 매 요청마다 새로 서명하며 1시간 뒤
 * 만료됩니다. 파일은 지우지 않으므로 다시 부르면 새 URL 을 받습니다.
 */
@Schema(description = "면접 다시보기 재생목록")
public record InterviewRecordingsResponse(

        String sessionId,

        @Schema(description = "답변 영상이 하나라도 있으면 true. 카메라를 한 번도 안 썼으면 false")
        boolean hasVideo,

        @Schema(description = "답변한 문항을 나간 순서대로. 되묻기는 원래 질문 바로 뒤에 옵니다")
        List<Item> items
) {

    public static InterviewRecordingsResponse of(String sessionId, List<Item> items) {
        boolean hasVideo = items.stream().anyMatch(item -> item.answerVideoUrl() != null);
        return new InterviewRecordingsResponse(sessionId, hasVideo, items);
    }

    @Schema(description = "재생목록의 문항 하나")
    public record Item(

            @Schema(description = "세션 안에서만 유일한 질문 ID", example = "q_1")
            String questionId,

            @Schema(description = "몇 번째 문항인지. 되묻기는 원래 질문과 같은 번호")
            int questionNumber,

            @Schema(description = "QUESTION | FOLLOWUP | REASK")
            String type,

            @Schema(description = "질문 문장. 질문 텍스트를 숨긴 면접이어도 내려줍니다")
            String questionText,

            @Schema(description = "면접관 질문 음성. 음성 합성이나 URL 서명에 실패하면 null")
            String questionAudioUrl,

            @Schema(description = "답변 녹음")
            String answerAudioUrl,

            @Schema(description = "답변 영상. 그 문항에서 카메라를 안 썼으면 null")
            String answerVideoUrl
    ) {
    }
}
