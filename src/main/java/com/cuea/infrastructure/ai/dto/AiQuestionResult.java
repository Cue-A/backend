package com.cuea.infrastructure.ai.dto;

/**
 * 작업 완료 시 돌아오는 결과.
 *
 * <p>{@code type} 은 question | followup | reask | session_end 네 가지입니다.
 * reask 는 category·difficulty 만 null 이고 나머지는 채워집니다.
 * audioUrl 은 TTS_FAILED 시 null 입니다.
 * isSpareTopic·isReplay 는 항상 포함되므로 nullable 처리가 필요 없습니다.
 */
@AiJson
public record AiQuestionResult(
        String type,
        String questionId,
        String text,
        String audioUrl,
        String category,
        String difficulty,
        Integer questionNumber,
        Integer questionTotal,
        Integer topicIndex,
        Integer topicTotal,
        Boolean isSpareTopic,
        Boolean isReplay,
        Integer totalQuestions
) {

    public static final String TYPE_QUESTION = "question";
    public static final String TYPE_FOLLOWUP = "followup";
    public static final String TYPE_REASK = "reask";
    public static final String TYPE_SESSION_END = "session_end";

    public boolean isSessionEnd() {
        return TYPE_SESSION_END.equals(type);
    }

    /** 되묻기는 문항 수에 포함되지 않습니다. 진행률 계산에서 제외하세요. */
    public boolean isReask() {
        return TYPE_REASK.equals(type);
    }
}
