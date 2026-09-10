package com.cuea.domain.document.entity;

/** 문서 종류. DB 에는 이름 그대로(대문자) 저장합니다. */
public enum DocType {

    /** 포트폴리오 */
    PORTFOLIO,

    /** 발표 자료 */
    PRESENTATION,

    /** 발표 대본 */
    SCRIPT
}
