package com.cuea.domain.report.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.report.dto.response.ReportDetailResponse;
import com.cuea.domain.report.entity.Report;
import com.cuea.domain.report.repository.ReportRepository;
import com.cuea.infrastructure.ai.AiReportResultReader;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>리포트와 질문을 한 번에 읽으므로 읽기 전용 트랜잭션으로 묶습니다. 외부 호출이 없어
 * 커넥션을 오래 붙들지 않습니다.
 */
@Service
@RequiredArgsConstructor
public class ReportQueryService {

    private final ReportRepository reportRepository;
    private final InterviewSessionQueryService sessionQueryService;
    private final AiReportResultReader resultReader;

    @Transactional(readOnly = true)
    public ReportDetailResponse getDetail(String userId, String reportId) {
        Report report = reportRepository.findByPublicIdAndSession_User_UserId(parse(reportId), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

        // switch 식이라 ReportStatus 가 늘면 여기서 컴파일이 깨집니다. 새 상태를 빠뜨리지 않게 하려는 것입니다.
        return switch (report.getStatus()) {
            case PROCESSING -> throw new BusinessException(ErrorCode.REPORT_NOT_READY);
            case FAILED -> throw new BusinessException(ErrorCode.REPORT_FAILED);
            case COMPLETED, PARTIAL -> ReportDetailResponse.of(report, readResult(report),
                    questionTexts(report.getSession().getSessionId()));
        };
    }

    /**
     * 저장 전에 모양을 확인하므로 정상이라면 실패하지 않습니다. 실패하면 우리 DB 의 값이
     * 문제라 502(AI 응답 문제)가 아니라 500 입니다. 다시 불러도 결과가 같습니다.
     */
    private AiReportResult readResult(Report report) {
        if (report.getReportData() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "끝난 리포트에 결과가 없습니다");
        }
        try {
            return resultReader.read(report.getReportData());
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "저장된 리포트를 해석할 수 없습니다", e);
        }
    }

    /**
     * 다른 도메인이라 리포지토리가 아니라 면접 서비스로 읽습니다. 소유권은 위에서 리포트를 본인 것으로
     * 찾았으므로 확인됐습니다. (session_id, question_id) 가 PK 라 ID 가 겹치지 않습니다.
     */
    private Map<String, String> questionTexts(String sessionId) {
        return sessionQueryService.findQuestionsInOrderForInternal(sessionId).stream()
                .collect(Collectors.toMap(Question::getQuestionId, Question::getText));
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
