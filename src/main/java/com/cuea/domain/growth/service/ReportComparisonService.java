package com.cuea.domain.growth.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.growth.dto.response.ReportComparisonResponse;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.interview.service.SessionRound;
import com.cuea.domain.report.service.FinishedReport;
import com.cuea.domain.report.service.FinishedReportQueryService;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiReportCompareRequest;
import com.cuea.infrastructure.ai.dto.AiReportComparison;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 회차 비교 · 성장 추이. 같은 1회차에서 시작한 회차 중 <b>이 리포트의 회차까지</b>를 AI 에 보내 비교합니다.
 * AI 는 리포트를 보관하지 않아 고른 회차의 원본을 모아 보내고, 결과는 저장하지 않고 부를 때마다 계산합니다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않습니다.</b> 읽기는 각 서비스의 짧은 트랜잭션에서 끝내고 AI 호출은
 * 그 밖에서 합니다. 다른 도메인에서는 엔티티가 아니라 값만 받아 지연 로딩이 걸리지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportComparisonService {

    private final FinishedReportQueryService reportQueryService;
    private final InterviewSessionQueryService sessionQueryService;
    private final AiClient aiClient;

    public ReportComparisonResponse compare(String userId, String reportId) {
        FinishedReport target = reportQueryService.getFinished(userId, reportId);
        List<ComparedRound> rounds = comparedRounds(userId, target);
        List<Integer> numbers = rounds.stream().map(ComparedRound::round).toList();

        long started = System.nanoTime();
        AiReportComparison result = callAi(rounds);
        log.info("AI 회차 비교 완료 sessionId={} rounds={} elapsedMs={}",
                target.sessionId(), numbers, (System.nanoTime() - started) / 1_000_000);

        requireSameRounds(result, numbers);
        return ReportComparisonResponse.of(target.reportId(),
                rounds.stream().map(ComparedRound::toResponse).toList(),
                result, sessionQueryService.findQuestionTextsForInternal(target.sessionId()));
    }

    /**
     * 이 리포트의 회차까지, 리포트가 끝난(COMPLETED · PARTIAL) 회차만 남깁니다. 중단된 재면접은 회차 번호에서부터
     * 빠지고, 리포트가 없거나 분석 중 · 실패인 회차는 번호를 남긴 채 빠집니다. 그래서 {@code [1, 3]} 처럼 빌 수
     * 있고 AI 도 그대로 받습니다. 이 리포트는 끝났으므로 마지막 회차는 항상 이 리포트의 회차입니다.
     */
    private List<ComparedRound> comparedRounds(String userId, FinishedReport target) {
        List<SessionRound> rounds = sessionQueryService.roundsUpTo(userId, target.sessionId());
        Map<String, FinishedReport> reports = reportQueryService.findFinishedBySessionIds(
                userId, rounds.stream().map(SessionRound::sessionId).toList());
        return rounds.stream()
                .filter(round -> reports.containsKey(round.sessionId()))
                .map(round -> new ComparedRound(round.round(), round.createdAt(), reports.get(round.sessionId())))
                .toList();
    }

    /**
     * AI 가 거절한 이유와 문구는 사용자에게 보여줄 것이 아니라 로그에만 남깁니다. 비교 요청은 Backend 가 조립하므로
     * AI 의 400 도 사용자 잘못이 아닙니다. 연결 실패 · 지연(503 · 504)만 그대로 두고 나머지는 502 입니다.
     */
    private AiReportComparison callAi(List<ComparedRound> rounds) {
        try {
            return aiClient.compareReports(new AiReportCompareRequest(rounds.stream()
                    .map(r -> new AiReportCompareRequest.Round(
                            r.report().sessionId(), r.round(), r.report().reportData()))
                    .toList()));
        } catch (BusinessException e) {
            ErrorCode code = e.getErrorCode() == ErrorCode.AI_UNAVAILABLE || e.getErrorCode() == ErrorCode.AI_TIMEOUT
                    ? e.getErrorCode() : ErrorCode.UNEXPECTED_AI_RESPONSE;
            throw new BusinessException(code, code.getMessage(), e);
        }
    }

    /**
     * 배열의 n번째 칸이 몇 회차인지는 {@code compared_rounds} 로만 알 수 있습니다. 보낸 회차와 다르면 그래프 점에
     * 다른 회차의 리포트를 걸게 되므로 내보내지 않습니다. 추이({@code trend})는 화면의 중심이라 같이 봅니다.
     */
    private static void requireSameRounds(AiReportComparison result, List<Integer> sent) {
        if (result == null || result.trend() == null || !sent.equals(result.comparedRounds())) {
            log.warn("AI 회차 비교 결과가 계약과 다릅니다 sent={} received={} trend={}", sent,
                    result == null ? null : result.comparedRounds(), result != null && result.trend() != null);
            throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE);
        }
    }

    /** 비교할 회차 하나. 회차 번호와 면접 시작 시각은 세션에서, 원본은 리포트에서 옵니다. */
    private record ComparedRound(int round, OffsetDateTime createdAt, FinishedReport report) {

        ReportComparisonResponse.Round toResponse() {
            return new ReportComparisonResponse.Round(round, report.reportId(), report.status(), createdAt);
        }
    }
}
