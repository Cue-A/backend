package com.cuea.domain.interview.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/interviews/{sessionId}/questions/{questionId}/bookmark 요청 본문.
 *
 * @param bookmarked 바뀔 상태. true 면 담고 false 면 뺍니다. primitive 로 두면 누락이 조용히
 *                   false 가 돼 북마크가 지워지므로 {@code Boolean} + {@code @NotNull} 입니다
 */
public record BookmarkToggleRequest(@NotNull Boolean bookmarked) {
}
