package com.cuea.domain.document.entity;

/**
 * 문서 파싱 진행 상태.
 *
 * <p>{@code READY} 가 되기 전에는 면접 세션을 시작할 수 없습니다.
 * AI 서버가 문서를 읽을 수 없는 상태이기 때문입니다.
 */
public enum DocumentStatus {

    /** 업로드만 끝난 상태. 기본값. */
    UPLOADED,

    /** AI 서버가 파싱 중. */
    PARSING,

    /** 파싱 완료. 세션에 붙일 수 있습니다. */
    READY,

    /** 파싱 실패. 재업로드가 필요합니다. */
    FAILED
}
