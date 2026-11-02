package com.cuea.domain.interview.dto.response;

import com.cuea.domain.interview.entity.InterviewSession;

/**
 * 현재 세션의 진행 옵션 조회 응답. {@code GET /api/interviews/{sessionId}} (Issue #99).
 *
 * <p>프론트 면접 화면이 질문 텍스트 숨김·면접관 스타일·답변 제한 시간을 서버에서 다시
 * 읽기 위한 API 입니다. 프론트가 시작 시 보낸 옵션을 자체 보관하지 않아도(새 탭·새로고침·
 * 다른 기기) 서버에 저장된 확정값을 진실 소스로 쓸 수 있습니다.
 *
 * <p><b>조회한 {@code sessionId} 세션 자신의 값입니다.</b> 재면접 세션을 조회하면 최초(root)
 * 세션이 아니라 해당 회차 세션에 저장된 옵션을 돌려줍니다(재면접은 질문 텍스트 숨김·타이머를
 * 변경할 수 있으므로 회차마다 다를 수 있습니다). 최초 세션의 <b>고정</b> 옵션이 필요하면
 * {@code GET /api/interviews/{sessionId}/replay-options} 를 쓰세요. 목적이 다른 별개 API 입니다.
 *
 * @param sessionId          조회한 세션 ID.
 * @param hideQuestionText   질문 텍스트를 숨기고 음성만 들려주는 실전 모드인지.
 * @param interviewerStyle   면접관 스타일. 프론트 계약에 맞춰 소문자 {@code friendly}·{@code pressure}.
 * @param answerTimeLimitSec 질문 1개당 답변 제한 시간(초). {@code 60}·{@code 90}·{@code 120} 중
 *                           하나이거나, 제한 없음이면 {@code null}. 타이머 입력이 연결되기 전
 *                           생성된 과거 세션은 저장된 값이 없어 {@code null} 로 내려갑니다(제한 없음).
 */
public record InterviewSessionOptionsResponse(
        String sessionId,
        boolean hideQuestionText,
        String interviewerStyle,
        Integer answerTimeLimitSec
) {

    /** 저장된 세션의 확정 옵션으로 변환합니다. enum 은 {@code toAiValue()} 로 소문자화합니다. */
    public static InterviewSessionOptionsResponse from(InterviewSession session) {
        return new InterviewSessionOptionsResponse(
                session.getSessionId(),
                session.isHideQuestionText(),
                session.getPersona().toAiValue(),
                session.getDurationLimit());
    }
}
