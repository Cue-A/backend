package com.cuea.infrastructure.websocket.message;

/**
 * 새 질문. type = "question"
 *
 * @param questionType   QUESTION | FOLLOWUP | REASK
 * @param audioUrl       TTS 실패 시 null. 프론트는 텍스트만으로도 진행할 수 있어야 합니다
 * @param audioAvailable audioUrl 이 없는 이유를 프론트가 구분할 수 있게 함께 보냅니다
 * @param questionNumber 되묻기에서는 올라가지 않습니다. 진행률은 이 값 기준
 */
public record QuestionPushMessage(
        String questionId,
        String questionType,
        String text,
        String audioUrl,
        boolean audioAvailable,
        String category,
        String difficulty,
        Integer questionNumber,
        Integer questionTotal
) {

    public static SocketMessage<QuestionPushMessage> of(QuestionPushMessage payload) {
        return SocketMessage.of("question", payload);
    }
}
