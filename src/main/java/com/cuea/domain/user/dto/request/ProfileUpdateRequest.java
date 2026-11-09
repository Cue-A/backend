package com.cuea.domain.user.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 프로필 부분 수정. 보낸 필드만 반영합니다. {@code userId}/{@code email}은 요청으로
 * 받지 않고(토큰으로 식별, 이메일은 계정 설정에서만), {@code profileImageUrl}도 이
 * 바디로 받지 않습니다 — 전용 업로드 API로만 바뀝니다.
 */
public record ProfileUpdateRequest(

        @Size(max = 50)
        String userName,

        @Size(max = 500)
        String introduction,

        @Size(max = 50)
        String targetJobRole,

        /** 허용값: student/entry/junior/experienced. 형식 검증은 서비스가 합니다. */
        String careerLevel,

        @Size(max = 100)
        String schoolName,

        @Size(max = 100)
        String major,

        Integer graduationYear
) {

    public boolean isEmpty() {
        return userName == null && introduction == null && targetJobRole == null
                && careerLevel == null && schoolName == null && major == null
                && graduationYear == null;
    }
}
