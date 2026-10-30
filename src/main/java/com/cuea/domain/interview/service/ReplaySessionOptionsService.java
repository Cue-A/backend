package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
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
 * <p>소유권 검증과 root 세션 조회는 {@link InterviewStartService#resolveReplayPlan} 의
 * 1~3단계와 같은 로직을 {@link InterviewSessionQueryService} 로 재사용합니다. 새 쿼리나
 * 소유권 로직을 중복 구현하지 않습니다.
 */
@Service
@RequiredArgsConstructor
public class ReplaySessionOptionsService {

    private final InterviewSessionQueryService sessionQueryService;

    /**
     * 전달한 세션 ID 로 소유권을 확인하고, 그 세션의 최초 root 세션 옵션을 돌려줍니다.
     * 전달한 세션이 2·3회차여도 최초 세션의 옵션을 반환합니다.
     *
     * <ol>
     *   <li>전달 세션이 요청 사용자 소유인지 확인({@code SESSION_NOT_FOUND}, 404). 없거나
     *       남의 세션이면 구별 없이 404 입니다.</li>
     *   <li>완료된 세션만 재연습 대상이므로 완료 상태인지 확인({@code SESSION_NOT_COMPLETED},
     *       409). 재연습 시작 API({@link InterviewStartService})와 같은 정책입니다.</li>
     *   <li>{@link InterviewSession#rootSessionId()} 로 최초 세션을 결정합니다(직전 회차가
     *       아니라 항상 최초).</li>
     *   <li>{@link InterviewSessionQueryService#getRootForReplay} 로 root 를 읽습니다. root
     *       소유권을 다시 확인하고({@code SESSION_NOT_FOUND}), 옵션에 쓰는 LAZY 연관
     *       ({@code document}·{@code company})을 fetch join 으로 초기화해 트랜잭션 밖에서도
     *       안전하게 DTO 로 변환합니다.</li>
     * </ol>
     *
     * <p>삭제된 문서나 {@code verified=false} 기업이 연결된 과거 세션이어도 <b>조회는 막지
     * 않습니다.</b> 과거 세션 정보 조회와 실제 재연습 시작 가능 여부는 별개이며, 시작 가능
     * 여부는 재연습 시작 API 가 최종 판정합니다({@code findUsableFileDocument} 는
     * {@code DOCUMENT_NOT_FOUND}, {@code findVerifiedCompany} 는 {@code INVALID_REQUEST}).
     * 조회는 저장된 최초 옵션을 그대로 보여주는 역할만 합니다.
     */
    @Transactional(readOnly = true)
    public ReplaySessionOptionsResponse getReplayOptions(String userId, String sessionId) {
        InterviewSession source = sessionQueryService.getOwnedSession(sessionId, userId);

        if (!source.getStatus().isReportable()) {
            throw new BusinessException(ErrorCode.SESSION_NOT_COMPLETED,
                    "완료된 세션만 재연습 옵션을 조회할 수 있습니다");
        }

        InterviewSession rootSession =
                sessionQueryService.getRootForReplay(source.rootSessionId(), userId);

        return ReplaySessionOptionsResponse.from(rootSession);
    }
}
