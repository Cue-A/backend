package com.cuea.domain.interview.dto.request;

/**
 * 폴더 만들기 · 이름 바꾸기 요청 본문.
 *
 * <p>검증 애너테이션을 달지 않았습니다. 길이를 앞뒤 공백을 자른 뒤 세야 해서 규칙은
 * {@code FolderName} 한 곳에 있습니다.
 *
 * @param folderName 앞뒤 공백 제거 후 1~50자
 */
public record FolderNameRequest(String folderName) {
}
