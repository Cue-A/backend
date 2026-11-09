package com.cuea.domain.interview.dto.request;

/**
 * 내 질문 추가 · 수정 요청 본문.
 *
 * <p>검증 애너테이션을 달지 않았습니다. 길이를 앞뒤 공백을 자른 뒤 세야 해서 규칙은
 * {@code CustomQuestionRule} 한 곳에 있습니다.
 *
 * @param text     앞뒤 공백 제거 후 1~200자
 * @param category 인성 · 경험 · 지원동기 · 직무 · 기타
 */
public record CommonQuestionRequest(String text, String category) {
}
