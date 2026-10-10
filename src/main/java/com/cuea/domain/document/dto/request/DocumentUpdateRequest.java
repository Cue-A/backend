package com.cuea.domain.document.dto.request;

/**
 * PATCH /api/documents/{documentId} 요청 본문.
 *
 * <p>지금은 제목만 바꿀 수 있습니다. 마크다운 본문 수정은 Issue #75 입니다.
 *
 * <p>검증 애너테이션을 달지 않았습니다. 길이를 앞뒤 공백을 자른 뒤 세야 해서
 * {@code @Size} 로는 등록 때와 같은 규칙을 표현할 수 없습니다. 규칙은
 * {@code DocumentTitle} 한 곳에 있습니다.
 *
 * @param title 새 제목. 앞뒤 공백 제거 후 1~100자
 */
public record DocumentUpdateRequest(String title) {
}
