package com.cuea.domain.interview.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/common-questions/{questionId}/favorite 요청 본문.
 *
 * @param favorite 바뀔 상태. primitive 로 두면 누락이 조용히 false 가 돼 즐겨찾기가 풀리므로
 *                 {@code Boolean} + {@code @NotNull} 입니다
 */
public record CommonQuestionFavoriteRequest(@NotNull Boolean favorite) {
}
