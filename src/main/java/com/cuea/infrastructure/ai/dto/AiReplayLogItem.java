package com.cuea.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 재연습 요청에 함께 보내는 1회차 로그 한 줄.
 *
 * <p>주질문은 text·category·is_spare_topic 까지, 꼬리질문은 difficulty 만 담습니다.
 * 꼬리질문의 difficulty 를 빠뜨리면 난이도가 달라져 회차 비교가 무의미해집니다.
 * reask 는 배열에서 제외합니다. docs/12-replay.md 참고.
 */
@AiJson
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiReplayLogItem(
        String type,
        String text,
        String category,
        String difficulty,
        Boolean isSpareTopic
) {

    public static AiReplayLogItem question(String text, String category,
                                           String difficulty, boolean spareTopic) {
        return new AiReplayLogItem("question", text, category, difficulty, spareTopic);
    }

    public static AiReplayLogItem followup(String difficulty) {
        return new AiReplayLogItem("followup", null, null, difficulty, null);
    }
}
