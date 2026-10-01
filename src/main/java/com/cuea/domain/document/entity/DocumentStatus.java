package com.cuea.domain.document.entity;

/**
 * 문서의 내부 처리 상태. DB 의 {@code status} 컬럼입니다.
 *
 * <p>프론트로 내보내지 않습니다. 인덱싱은 면접 세션을 만들 때 AI 서버가 하므로
 * 문서는 등록 즉시 {@code READY} 이고, 프론트가 기다릴 상태가 없습니다(Issue #57).
 */
public enum DocumentStatus {

    /** 저장은 끝났지만 아직 본문을 읽지 못한 상태. */
    UPLOADED,

    /** AI 인덱싱 진행 중. */
    PARSING,

    /** 면접에 쓸 수 있는 상태. */
    READY,

    FAILED
}
