package com.cuea.domain.interview.dto.response;

import com.cuea.domain.interview.entity.InterviewSession;

/**
 * POST /api/interviews 응답.
 *
 * <p>첫 질문 본문은 여기 담지 않습니다. WebSocket({@code /ws/interviews/{sessionId}})
 * 으로 별도 push 되므로, 프론트는 이 응답을 받은 즉시 소켓에 연결해야 합니다.
 *
 * <p><b>세션 옵션 3필드({@code hideQuestionText}·{@code interviewerStyle}·
 * {@code answerTimeLimitSec})는 요청값이 아니라 실제 저장된 세션의 확정값입니다</b>
 * (Issue #99). 프론트가 시작 직후 세션 옵션을 알 수 있어, 별도 조회
 * ({@code GET /api/interviews/{sessionId}}) 없이도 면접 화면 설정을 맞출 수 있습니다.
 * 두 경로의 옵션 필드 이름·형식은 같습니다.
 *
 * @param sessionId        AI 가 발급한 세션 ID. 이후 모든 면접 API 의 경로 변수로 씁니다.
 * @param questionTotal    사용자가 선택한 총 문항 수(되묻기 제외).
 * @param replayApplied    이 세션이 재면접(replay)으로 시작됐는지. 일반 면접({@code sourceSessionId}
 *                         없음)이면 false, 정상 재면접이면 true 입니다. 재면접 요청의 고정 옵션이
 *                         최초 세션과 다르면 {@code INVALID_REQUEST}(400)로 거부되어 이 응답 자체가
 *                         내려가지 않습니다(일반 면접으로 자동 전환하지 않습니다).
 * @param hideQuestionText 질문 텍스트를 숨기고 음성만 들려주는 실전 모드인지(저장된 값).
 * @param interviewerStyle 면접관 스타일. 프론트 계약에 맞춰 소문자 {@code friendly}·{@code pressure}
 *                         로 내려갑니다(엔티티 enum 은 대문자지만 {@code Persona.toAiValue()} 로 변환).
 * @param answerTimeLimitSec 질문 1개당 답변 제한 시간(초). {@code 60}·{@code 90}·{@code 120} 중
 *                         하나이거나, 제한 없음이면 {@code null} 입니다(과거 세션도 null).
 *                         <p>기존 필드({@code sessionId}·{@code questionTotal}·{@code replayApplied})
 *                         는 그대로라 이 필드들을 읽지 않는 기존 클라이언트는 영향받지 않습니다
 *                         (필드 추가뿐, 하위 호환).
 */
public record InterviewStartResponse(
        String sessionId,
        Integer questionTotal,
        boolean replayApplied,
        boolean hideQuestionText,
        String interviewerStyle,
        Integer answerTimeLimitSec
) {

    /**
     * 저장된 세션의 확정 옵션으로 응답을 만듭니다. 요청 DTO 가 아니라 DB 에 저장된
     * {@link InterviewSession} 을 기준으로 구성해, 응답과 실제 저장 상태가 어긋나지 않게 합니다.
     *
     * @param session       저장이 끝난 세션(옵션 확정값 보유)
     * @param questionTotal AI 가 확정한 총 문항 수
     * @param replayApplied 재면접으로 시작됐는지
     */
    public static InterviewStartResponse from(InterviewSession session,
                                              Integer questionTotal,
                                              boolean replayApplied) {
        return new InterviewStartResponse(
                session.getSessionId(),
                questionTotal,
                replayApplied,
                session.isHideQuestionText(),
                session.getPersona().toAiValue(),
                session.getDurationLimit());
    }
}
