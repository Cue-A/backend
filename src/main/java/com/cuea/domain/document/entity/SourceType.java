package com.cuea.domain.document.entity;

/**
 * 문서를 어떻게 받았는지.
 *
 * <p>어느 컬럼을 읽어야 하는지가 이 값으로 갈립니다.
 * {@code FILE} 이면 {@code doc_url}, {@code MARKDOWN} 이면 {@code doc_text} 입니다.
 */
public enum SourceType {

    /** 파일 업로드. {@code doc_url} 과 {@code file_format} 이 채워집니다. */
    FILE,

    /** 사용자가 직접 입력. {@code doc_text} 가 채워집니다. */
    MARKDOWN
}
