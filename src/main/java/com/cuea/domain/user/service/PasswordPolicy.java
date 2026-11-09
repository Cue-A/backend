package com.cuea.domain.user.service;

import java.util.regex.Pattern;

/**
 * 비밀번호 형식 규칙. 가입(회원가입)과 비밀번호 변경이 같은 규칙을 쓴다.
 *
 * <p>8~64자, 영문·숫자 최소 1개씩. 코드·문서 어디에도 정책이 없어 임시로 정한 값입니다.
 */
public final class PasswordPolicy {

    private static final Pattern PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        return PATTERN.matcher(password).matches();
    }
}
