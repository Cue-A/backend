package com.cuea.domain.interview.dto.response;

/**
 * POST /api/interviews 응답.
 *
 * <p>첫 질문 본문은 여기 담지 않습니다. WebSocket({@code /ws/interviews/{sessionId}})
 * 으로 별도 push 되므로, 프론트는 이 응답을 받은 즉시 소켓에 연결해야 합니다.
 *
 * @param sessionId     AI 가 발급한 세션 ID. 이후 모든 면접 API 의 경로 변수로 씁니다.
 * @param questionTotal 사용자가 선택한 총 문항 수(되묻기 제외).
 * @param replayApplied 이 세션이 재면접(replay)으로 시작됐는지. 일반 면접이면 false,
 *                      옵션 변경으로 일반 면접으로 전환됐어도 false, 정상 재면접이면 true.
 *                      기존 필드는 그대로라 이 필드를 읽지 않는 기존 클라이언트는 영향받지
 *                      않습니다(필드 추가뿐, 하위 호환).
 */
public record InterviewStartResponse(
        String sessionId,
        Integer questionTotal,
        boolean replayApplied
) {
}
