package com.cuea.domain.interview.entity;

/**
 * 면접 세션 상태. 값은 docs/11-interview.md 기준입니다.
 *
 * <p><b>AI 서버도 자기 세션 상태를 따로 들고 있습니다.</b> 진행 중인 토픽,
 * 꼬리질문 횟수 같은 것들이며 이것과는 다른 개념입니다.
 * 사용자에게 보이는 상태는 항상 이 값입니다.
 */
public enum SessionStatus {

    /** 세션 생성 + AI 첫 응답 수신. */
    IN_PROGRESS,

    /** {@code session_end} 수신. 리포트를 만들 수 있습니다. */
    COMPLETED,

    /** 사용자 중단 / 타임아웃 / 복구 불가 오류. 리포트를 만들지 않습니다. */
    ABORTED;

    /** 리포트 생성 대상인지. {@code ABORTED} 세션은 제외됩니다. */
    public boolean isReportable() {
        return this == COMPLETED;
    }
}
