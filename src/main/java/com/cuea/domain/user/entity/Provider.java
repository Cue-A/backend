package com.cuea.domain.user.entity;

/** 로그인 수단. DB 에는 이름 그대로 저장합니다. */
public enum Provider {

    /** 이메일 + 비밀번호 */
    LOCAL,

    /** 카카오 */
    KAKAO
}
