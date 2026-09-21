package com.cuea.domain.interview.entity;

import java.util.Locale;

/**
 * 면접관의 압박 강도. AI 계약의 {@code persona} 필드에 대응합니다.
 *
 * <p>DB·자바에서는 {@code FRIENDLY}·{@code PRESSURE} 로 두지만, AI 요청 본문에는
 * 소문자 {@code friendly}·{@code pressure} 로 직렬화해야 합니다. {@link #toAiValue()}
 * 를 거치지 않고 {@code name()} 을 그대로 보내지 마세요.
 */
public enum Persona {

    /** 순한맛. */
    FRIENDLY,

    /** 매운맛. */
    PRESSURE;

    /** AI 요청 본문에 넣을 소문자 값. */
    public String toAiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** AI 계약의 소문자 값({@code friendly}/{@code pressure})을 enum 으로. */
    public static Persona fromAiValue(String value) {
        return Persona.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
