package com.cuea.domain.growth.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.growth.dto.response.ReportComparisonResponse;
import com.cuea.domain.interview.entity.Question;
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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 회차 비교 · 성장 추이. 같은 1회차에서 시작한 회차 중 <b>이 리포트의 회차까지</b>를 AI 에 보내 비교합니다.
 * 2회차 리포트에서 부르면 1~2회차, 3회차 리포트에서 부르면 1~3회차입니다.
 *
 * <p>AI 는 리포트를 보관하지 않아 고른 회차의 원본({@code report_data})을 모아 보냅니다. 결과는 저장하지 않고
 * 부를 때마다 계산합니다. LLM 을 쓰지 않아 같은 입력이면 같은 결과이고 수 ms 안에 끝납니다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않습니다.</b> 읽기는 각 서비스의 짧은 트랜잭션에서 끝내고 AI 호출은
 * 그 밖에서 합니다. 리포트와 회차는 엔티티가 아니라 값({@link FinishedReport} · {@link SessionRound})으로 받아
 * 트랜잭션 밖에서 지연 로딩이 걸리지 않습니다.
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
        AiReportComparison result = aiClient.compareReports(request(rounds));
        log.info("AI 회차 비교 완료 sessionId={} rounds={} elapsedMs={}",
                target.sessionId(), numbers, (System.nanoTime() - started) / 1_000_000);

        requireSameRounds(result, numbers);
        return ReportComparisonResponse.of(target.reportId(), rounds, result, questionTexts(target.sessionId()));
    }

    /**
     * 같은 1회차에서 시작한 회차 중 이 리포트의 회차까지, 리포트가 끝난(COMPLETED · PARTIAL) 것만 남깁니다.
     * 중단된 재면접은 회차 번호에서부터 빠지고, 리포트가 없거나 분석 중 · 실패인 회차는 번호를 남긴 채
     * 빠집니다. 그래서 {@code [1, 3]} 처럼 빌 수 있고 AI 도 그대로 받습니다.
     *
     * <p>이 리포트는 끝난 것을 확인했으므로 마지막 회차는 항상 이 리포트의 회차입니다.
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

    private static AiReportCompareRequest request(List<ComparedRound> rounds) {
        return new AiReportCompareRequest(rounds.stream()
                .map(r -> new AiReportCompareRequest.Round(r.report().sessionId(), r.round(), r.report().reportData()))
                .toList());
    }

    /**
     * 배열의 n번째 칸이 몇 회차인지는 {@code compared_rounds} 로만 알 수 있습니다. 보낸 회차와 다르면 그래프 점에
     * 다른 회차의 리포트를 걸게 되므로 내보내지 않습니다. 추이({@code trend})는 화면의 중심이라 같이 봅니다.
     */
    private static void requireSameRounds(AiReportComparison result, List<Integer> sent) {
        if (result == null || result.trend() == null) {
            throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "회차 비교 결과에 필수 필드가 없습니다: trend");
        }
        if (!sent.equals(result.comparedRounds())) {
            log.warn("AI 회차 비교 회차 불일치 sent={} received={}", sent, result.comparedRounds());
            throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "회차 비교 결과의 회차가 보낸 회차와 다릅니다");
        }
    }

    /**
     * 질문 ID 는 회차마다 같습니다. 재면접이 1회차 순서를 그대로 재생하기 때문입니다. 그래서 이 리포트 세션의
     * 질문에서 찾습니다. 소유권은 리포트를 본인 것으로 찾을 때 확인했습니다.
     */
    private Map<String, String> questionTexts(String sessionId) {
        return sessionQueryService.findQuestionsInOrderForInternal(sessionId).stream()
                .collect(Collectors.toMap(Question::getQuestionId, Question::getText));
    }
}
