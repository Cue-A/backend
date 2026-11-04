package com.cuea.domain.interview.dto.request;

/**
 * 질문은행 메모 쓰기 · 고치기 요청 본문.
 *
 * <p>검증 애너테이션을 달지 않았습니다. 빈 값은 오류가 아니라 지우기라서 규칙은
 * {@code BookmarkMemo} 한 곳에 있습니다.
 *
 * @param memo 최대 500자. 없거나 비었거나 공백뿐이면 메모를 지웁니다
 */
public record BookmarkMemoRequest(String memo) {
}
