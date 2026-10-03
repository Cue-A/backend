package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.domain.report.dto.response.ReportDetailResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.infrastructure.ai.AiReportResultReader;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 리포트 상세 조회. <b>본인 리포트만 보입니다.</b>
 *
 * <p>끝난 리포트(COMPLETED · PARTIAL)만 내려줍니다. 진행 중이면 202 {@code REPORT_NOT_READY},
 * 실패했으면 409 {@code REPORT_FAILED} 입니다. 프론트는 상태 조회나 WebSocket 으로 끝난 걸
 * 확인한 뒤 부르므로, 보통은 여기서 걸리지 않습니다.
 *
 * <p>상태 조회와 같은 이유로 트랜잭션을 걸지 않습니다. 세션은 지연 로딩 프록시의 식별자만
 * 쓰고, 질문 조회는 따로 한 번 합니다.
 */
@Service
@RequiredArgsConstructor
public class ReportQueryService {

    private final ReportRepository reportRepository;
    private final QuestionRepository questionRepository;
    private final AiReportResultReader resultReader;

    public ReportDetailResponse getDetail(String userId, String reportId) {
        Report report = reportRepository.findByPublicIdAndSession_User_UserId(parse(reportId), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

        switch (report.getStatus()) {
            case PROCESSING -> throw new BusinessException(ErrorCode.REPORT_NOT_READY);
            case FAILED -> throw new BusinessException(ErrorCode.REPORT_FAILED);
            case COMPLETED, PARTIAL -> {
            }
        }
        if (report.getReportData() == null) {
            // finish() 가 결과와 상태를 같이 쓰므로 정상이라면 올 수 없습니다.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "끝난 리포트에 결과가 없습니다");
        }

        AiReportResult result = resultReader.read(report.getReportData());
        return ReportDetailResponse.of(report, result, questionTexts(report.getSession().getSessionId()));
    }

    private Map<String, String> questionTexts(String sessionId) {
        return questionRepository.findAllBySessionId(sessionId).stream()
                .collect(Collectors.toMap(Question::getQuestionId, Question::getText, (a, b) -> a));
    }

    /** UUID 가 아닌 값도 없는 리포트와 같은 404 입니다. 400 으로 구별해 줄 이유가 없습니다. */
    private UUID parse(String reportId) {
        try {
            return UUID.fromString(reportId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
        }
    }
}
