package com.cuea.domain.interview.service;

import com.cuea.domain.interview.dto.response.ReplaySessionOptionsResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재연습을 시작하기 전에 <b>최초(root) 세션의 고정 옵션</b>을 조회합니다.
 * {@code GET /api/interviews/{sessionId}/replay-options}.
 *
 * <p>재연습은 최초 1회차와 같은 조건으로만 시작할 수 있고(docs/12-replay.md 옵션 고정
 * 정책), 다르면 {@link InterviewStartService} 가 {@code INVALID_REQUEST}(400)로 거부합니다.
 * 프론트는 이 조회로 최초 세션의 옵션을 받아 자동으로 채운 뒤 재연습을 시작합니다.
 *
 * <p><b>조회 전용입니다. AI 서버를 호출하지 않고 백엔드 DB 만 읽습니다.</b> 재연습의
 * 진실 소스가 백엔드 DB 라는 점과 일치합니다(docs/12-replay.md "진실 소스").
 *
 * <p>소유권 검증·완료 상태 확인·root 세션 조회는 재연습 시작 흐름
 * ({@link InterviewStartService#resolveReplayPlan})과 <b>같은 메서드
 * ({@link InterviewSessionQueryService#getReplayRoot})를 공유</b>합니다. 양쪽이 같은 단계를
 * 복사하지 않아, 한쪽만 바뀌어 조회는 되는데 시작은 실패하는 불일치가 생기지 않습니다.
 * 새 쿼리나 소유권 로직을 중복 구현하지 않습니다(docs/12-replay.md).
 */
@Service
@RequiredArgsConstructor
public class ReplaySessionOptionsService {

    private final InterviewSessionQueryService sessionQueryService;

    /**
     * 전달한 세션 ID 로 소유권·완료 상태를 확인하고, 그 세션의 최초 root 세션 옵션을
     * 돌려줍니다. 전달한 세션이 2·3회차여도 최초 세션의 옵션을 반환합니다.
     *
     * <p>소유권 확인({@code SESSION_NOT_FOUND}, 404) → 완료 상태 확인
     * ({@code SESSION_NOT_COMPLETED}, 409) → root fetch join 조회(LAZY {@code document}·
     * {@code company} 초기화)까지는 {@link InterviewSessionQueryService#getReplayRoot} 가
     * 재연습 시작 API 와 공통으로 처리합니다. 이 서비스는 그 root 를 DTO 로 변환만 합니다.
     *
     * <p>삭제된 문서나 {@code verified=false} 기업이 연결된 과거 세션이어도 <b>조회는 막지
     * 않습니다.</b> 과거 세션 정보 조회와 실제 재연습 시작 가능 여부는 별개이며, 시작 가능
     * 여부는 재연습 시작 API 가 최종 판정합니다({@code findUsableFileDocument} 는
     * {@code DOCUMENT_NOT_FOUND}, {@code findVerifiedCompany} 는 {@code INVALID_REQUEST}).
     * 조회는 저장된 최초 옵션을 그대로 보여주는 역할만 합니다.
     */
    @Transactional(readOnly = true)
    public ReplaySessionOptionsResponse getReplayOptions(String userId, String sessionId) {
        InterviewSession rootSession = sessionQueryService.getReplayRoot(sessionId, userId);

        return ReplaySessionOptionsResponse.from(rootSession);
    }
}
