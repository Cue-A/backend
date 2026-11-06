package com.cuea.domain.growth.badge;

/**
 * 지급·조회 로직이 지목하는 배지의 안정적인 키(Issue #131, 총 5종).
 *
 * <p>DB {@code badge.badge_code} 와 1:1 입니다. 코드에서 배지를 고를 때는 {@code badge_id}
 * (연속 정수, 시드 재적재로 바뀔 수 있음)가 아니라 이 enum 을 씁니다. {@code Badge} 엔티티
 * 주석과 같은 규약입니다.
 *
 * <p>{@code tone} 은 프론트(성장 관리 {@code badges[].tone})가 요구하지만 {@code badge}
 * 테이블에 별도 컬럼이 없어, 여기(코드)와 시드({@code condition_data.tone})에 같은 값을 둡니다.
 * 조회 응답은 이 enum 의 값을 내려, 시드가 비어도 표시가 깨지지 않게 합니다.
 * 값은 프론트 {@code home/types/home.ts} {@code BadgeTone} 과 홈 배지 목업을 따릅니다.
 */
public enum BadgeCode {

    /** 첫 면접 완료. */
    FIRST_SESSION("brand"),

    /** KST 3일 연속 완료. */
    STREAK_3("warning"),

    /** 압박(PRESSURE) 페르소나 세션 완료. */
    PRESSURE_CLEAR("danger"),

    /** 유효 리포트 점수 3회 연속 상승. */
    RISING("info"),

    /** 누적 완료 10회. */
    TEN_SESSIONS("success");

    private final String tone;

    BadgeCode(String tone) {
        this.tone = tone;
    }

    /** 성장 관리 응답의 {@code tone}(brand/warning/danger/info/success). */
    public String tone() {
        return tone;
    }
}
