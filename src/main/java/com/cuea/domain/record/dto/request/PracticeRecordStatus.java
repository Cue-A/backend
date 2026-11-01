package com.cuea.domain.record.dto.request;

import com.cuea.domain.interview.entity.SessionStatus;

/** 연습 기록 상태 탭. 중단된 세션은 목록에 나오지 않아 값이 없습니다. */
public enum PracticeRecordStatus {

    COMPLETED,

    IN_PROGRESS;

    public SessionStatus toSessionStatus() {
        return SessionStatus.valueOf(name());
    }
}
