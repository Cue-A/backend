package com.cuea.domain.interview.entity;

/**
 * 질문의 종류. AI 응답의 {@code type} 필드({@code question|followup|reask})에 대응합니다.
 *
 * <p>{@code category} 와 달리 값이 3개로 고정된 백엔드 개념이라 enum 으로 둡니다.
 * {@code category}(한글 8종)를 enum 으로 만들지 않는 이유는 {@link Question} 참고.
 */
public enum QuestionType {

    /** 토픽의 시작점이 되는 주질문. 문항 수에 포함됩니다. */
    QUESTION,

    /** 직전 답변의 허점을 파고드는 꼬리질문. 문항 수에 포함됩니다. */
    FOLLOWUP,

    /**
     * 답변이 부실해 같은 질문을 다시 요청하는 되묻기.
     *
     * <p><b>문항 수에 포함되지 않습니다.</b> {@code questionNumber} 가 올라가지
     * 않고 {@code category}·{@code difficulty} 가 null 이며 두 플래그는 항상
     * false 입니다. 한도는 토픽당 1회 / 세션당 3회.
     */
    REASK;

    /** 문항 수에 포함되는 질문인지. 되묻기만 제외됩니다. */
    public boolean countsTowardQuestionCount() {
        return this != REASK;
    }
}
