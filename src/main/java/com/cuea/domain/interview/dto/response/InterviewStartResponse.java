package com.cuea.domain.interview.dto.response;

/**
 * POST /api/interviews 응답.
 *
 * <p>첫 질문 본문은 여기 담지 않습니다. WebSocket({@code /ws/interviews/{sessionId}})
 * 으로 별도 push 되므로, 프론트는 이 응답을 받은 즉시 소켓에 연결해야 합니다.
 *
 * @param sessionId     AI 가 발급한 세션 ID. 이후 모든 면접 API 의 경로 변수로 씁니다.
 * @param questionTotal 사용자가 선택한 총 문항 수(되묻기 제외).
 */
public record InterviewStartResponse(
        String sessionId,
        Integer questionTotal
) {
}
