package com.cuea.domain.report.entity;

/**
 * 리포트 생성 상태. Backend 가 정하는 값이라 enum 입니다.
 *
 * <p>AI 의 {@code report_status}(complete | partial)는 이 값으로 옮기고, 원문은
 * {@code report_data} 에 그대로 남습니다.
 */
public enum ReportStatus {

    /** AI 가 분석 중. */
    PROCESSING,

    /** 세 축 모두 성공. 카메라 미사용으로 시선이 {@code skipped} 인 것도 여기입니다. */
    COMPLETED,

    /** 말하기 또는 시선 축이 실패했고 나머지 축으로 만든 리포트. AI {@code report_status: partial}. */
    PARTIAL,

    /** 리포트를 만들지 못함. 같은 행으로 다시 요청할 수 있습니다. */
    FAILED;

    /** 등록 API 로 다시 요청해도 되는 상태인지. */
    public boolean isRequestable() {
        return this == FAILED;
    }
}
