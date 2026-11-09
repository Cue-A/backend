package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.SessionStatus;

/**
 * 진행 중인 세션에서만 되는 요청(답변 제출 · 면접 중 북마크)의 상태 규칙. 같은 오류를 내도록 한 곳에 둡니다.
 *
 * <p>정상 종료(COMPLETED)와 중단(ABORTED)을 구분해 각각 {@code SESSION_ENDED} ·
 * {@code SESSION_ABORTED} 로 알립니다. 클라이언트가 두 상황을 다르게 안내할 수 있어야 하기 때문입니다.
 */
final class ActiveSession {

    private ActiveSession() {
    }

    /** 진행 중이면 그대로 돌려줍니다. */
    static InterviewSession require(InterviewSession session) {
        SessionStatus status = session.getStatus();
        if (status == SessionStatus.ABORTED) {
            throw new BusinessException(ErrorCode.SESSION_ABORTED);
        }
        if (status != SessionStatus.IN_PROGRESS) {
            // COMPLETED 등 진행 중이 아닌 나머지는 이미 종료된 세션으로 취급합니다.
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        return session;
    }
}
