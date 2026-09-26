package com.cuea.domain.report.service;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.service.CompanyProfileFormatter;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.infrastructure.ai.dto.AiReportAnswer;
import com.cuea.infrastructure.ai.dto.AiReportRequest;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 세션 로그로 AI 리포트 요청 본문을 만듭니다.
 *
 * <p>첫 요청과 자동 재시도가 함께 씁니다. 재시도({@code MEDIA_FETCH_FAILED})는
 * presigned URL 이 만료됐을 가능성이 있어, 매번 새로 조립해 URL 도 새로 발급합니다.
 *
 * <p><b>읽기 트랜잭션 안에서 조립합니다.</b> 세션의 회사가 LAZY 이고 {@code open-in-view}
 * 가 꺼져 있습니다. presigned URL 발급은 서명 계산이라 네트워크를 타지 않아 트랜잭션
 * 안에 있어도 커넥션을 오래 붙들지 않습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportRequestAssembler {

    private final InterviewSessionQueryService sessionQueryService;
    private final CompanyProfileFormatter companyProfileFormatter;
    private final PresignedUrlIssuer presignedUrlIssuer;

    @Transactional(readOnly = true)
    public AiReportRequest build(String sessionId) {
        InterviewSession session = sessionQueryService.getSessionForInternal(sessionId);
        Company company = session.getCompany();

        List<AiReportAnswer> answers = sessionQueryService.findQuestionsInOrderForInternal(sessionId).stream()
                .filter(question -> hasAnswer(sessionId, question))
                .map(this::toAnswer)
                .toList();

        return new AiReportRequest(
                session.getPersona().toAiValue(),
                session.getJobRole(),
                // 면접 시작 때 보낸 값과 같아야 합니다. InterviewStartService 참고.
                company == null ? null : String.valueOf(company.getCompanyId()),
                company == null ? null : companyProfileFormatter.format(company),
                answers);
    }

    /**
     * 답변 녹음이 없는 질문은 뺍니다.
     *
     * <p>빈 URL 을 보내면 AI 가 파일을 받지 못해 {@code MEDIA_FETCH_FAILED} 로 리포트
     * 전체가 실패합니다. 정상 흐름에서는 거의 없지만(답하기 전에 세션이 끝난 경우 등)
     * 한 문항 때문에 리포트를 통째로 잃지 않게 합니다.
     */
    private boolean hasAnswer(String sessionId, Question question) {
        String key = question.getAnswerAudioObjectKey();
        if (key == null || key.isBlank()) {
            log.warn("답변 녹음이 없어 리포트 요청에서 뺍니다 sessionId={} questionId={}",
                    sessionId, question.getQuestionId());
            return false;
        }
        return true;
    }

    private AiReportAnswer toAnswer(Question question) {
        String videoKey = question.getAnswerVideoObjectKey();
        return new AiReportAnswer(
                question.getQuestionId(),
                question.getType().name().toLowerCase(Locale.ROOT),
                question.getText(),
                // AI 가 준 문자열 그대로 돌려줍니다. 가운뎃점(·)까지 같아야 합니다.
                question.getCategory(),
                question.getDifficulty(),
                question.getQuestionNumber(),
                presignedUrlIssuer.issueRecordingDownload(question.getAnswerAudioObjectKey()),
                videoKey == null || videoKey.isBlank() ? null : presignedUrlIssuer.issueRecordingDownload(videoKey),
                question.isAnswerIsTimeout(),
                question.getReaskOf(),
                question.isReplay(),
                question.isSpareTopic());
    }
}
