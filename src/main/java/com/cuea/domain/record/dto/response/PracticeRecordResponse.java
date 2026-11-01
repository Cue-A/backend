package com.cuea.domain.record.dto.response;

import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.report.dto.response.ReportSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "연습 기록 한 건")
public record PracticeRecordResponse(

        String sessionId,

        @Schema(description = "회사를 고르지 않았으면 null")
        String companyName,

        String jobRole,

        @Schema(description = "FRIENDLY | PRESSURE")
        Persona persona,

        @Schema(description = "고른 문항 수. 되묻기는 세지 않습니다")
        int questionCount,

        @Schema(description = "COMPLETED | IN_PROGRESS")
        SessionStatus status,

        @Schema(description = "회차. 1부터이며 중단된 재면접은 세지 않습니다")
        int round,

        OffsetDateTime createdAt,

        @Schema(description = "리포트 요약. 리포트를 요청하지 않았으면 null")
        ReportSummary report
) {

    /** 회사는 목록 조회에서 함께 로드됩니다. */
    public static PracticeRecordResponse of(InterviewSession session, int round, ReportSummary report) {
        return new PracticeRecordResponse(
                session.getSessionId(),
                session.getCompany() == null ? null : session.getCompany().getCompanyName(),
                session.getJobRole(),
                session.getPersona(),
                session.getQuestionCount(),
                session.getStatus(),
                round,
                session.getCreatedAt(),
                report);
    }
}
