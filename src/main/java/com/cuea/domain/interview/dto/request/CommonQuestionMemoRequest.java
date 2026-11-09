package com.cuea.domain.interview.dto.request;

/**
 * PATCH /api/common-questions/{questionId}/memo 요청 본문.
 *
 * @param memo 최대 500자. 없거나 비었거나 공백뿐이면 메모를 지웁니다
 */
public record CommonQuestionMemoRequest(String memo) {
}
