package com.cuea.domain.interview.service;

import com.cuea.domain.interview.dto.response.InterviewSessionOptionsResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현재 세션의 진행 옵션을 조회합니다. {@code GET /api/interviews/{sessionId}} (Issue #99).
 *
 * <p>프론트 면접 화면이 질문 텍스트 숨김·면접관 스타일·답변 제한 시간을 서버에서 다시
 * 읽는 용도입니다. 시작 시 보낸 옵션을 프론트가 보관하지 않아도 서버 저장값을 진실
 * 소스로 쓰게 합니다.
 *
 * <p><b>조회 전용입니다. AI 서버를 호출하지 않고 백엔드 DB 만 읽습니다.</b> 소유권 검증은
 * {@link InterviewSessionQueryService#getOwnedSession} 을 재사용하며, 없거나 남의 세션이면
 * {@code SESSION_NOT_FOUND}(404) 입니다. 완료 여부와 무관하게(진행 중 포함) 조회됩니다.
 *
 * <p>재면접의 <b>최초 세션 고정 옵션</b>을 보는 {@link ReplaySessionOptionsService} 와는
 * 목적이 다릅니다. 여기는 조회한 세션 <b>자신</b>의 현재 옵션을 돌려줍니다(재면접 회차의
 * 변경된 숨김·타이머 값 포함).
 *
 * <p>응답에 쓰는 값({@code persona}·{@code hideQuestionText}·{@code durationLimit})은 모두
 * 세션 테이블의 직접 컬럼이라 LAZY 연관 초기화나 추가 조회(N+1)가 없습니다.
 */
@Service
@RequiredArgsConstructor
public class InterviewSessionOptionsService {

    private final InterviewSessionQueryService sessionQueryService;

    @Transactional(readOnly = true)
    public InterviewSessionOptionsResponse getSessionOptions(String userId, String sessionId) {
        InterviewSession session = sessionQueryService.getOwnedSession(sessionId, userId);

        return InterviewSessionOptionsResponse.from(session);
    }
}
