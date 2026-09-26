package com.cuea.infrastructure.ai.dto;

/**
 * 리포트 요청의 {@code answers[]} 항목. 질문 로그 한 행입니다.
 *
 * <p>{@code category} 는 AI 가 준 문자열 그대로 돌려줍니다. 가운뎃점(·)까지 같아야 합니다.
 * {@code audioUrl} · {@code videoUrl} 은 object key 로 만든 presigned GET URL 이며,
 * 영상이 없으면 {@code videoUrl} 을 null 로 보냅니다(시선 축이 {@code skipped} 가 됩니다).
 *
 * @param type     question | followup | reask
 * @param reaskOf  되묻기면 원 질문의 question_id. 아니면 null
 * @param isReplay 1회차와 같은 주질문인가. 안 보내면 회차 비교가 동작하지 않습니다
 */
@AiJson
public record AiReportAnswer(
        String questionId,
        String type,
        String text,
        String category,
        String difficulty,
        int questionNumber,
        String audioUrl,
        String videoUrl,
        boolean isTimeout,
        String reaskOf,
        boolean isReplay,
        boolean isSpareTopic
) {
}
