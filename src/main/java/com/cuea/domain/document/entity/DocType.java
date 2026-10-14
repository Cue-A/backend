package com.cuea.domain.document.entity;

/**
 * 사용자가 등록하는 문서의 종류.
 *
 * <p>면접 질문의 근거가 되는 문서만 받습니다. 초안에 있던 {@code PRESENTATION}·
 * {@code SCRIPT} 는 발표 코칭용이라 이번 범위에서 뺐습니다(Issue #28).
 * 다시 넣으려면 {@code docs/02-database.md} 의 문서 절도 함께 고치세요.
 */
public enum DocType {

    /** 자기소개서 · 이력서. */
    RESUME,

    /** 포트폴리오. */
    PORTFOLIO
}
