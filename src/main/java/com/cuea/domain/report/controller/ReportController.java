package com.cuea.domain.report.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.report.dto.response.ReportRequestResponse;
import com.cuea.domain.report.dto.response.ReportStatusResponse;
import com.cuea.domain.report.service.ReportRequestService;
import com.cuea.domain.report.service.ReportStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리포트 API.
 *
 * <p>등록은 면접 세션의 하위 자원이라 {@code /api/interviews/{sessionId}/reports} 이고,
 * 등록한 뒤로는 리포트 자원 {@code /api/reports/{reportId}} 아래를 씁니다.
 * 결과는 REST 로 기다리지 않고 {@code /ws/reports/{reportId}} 로 받습니다.
 */
@Tag(name = "리포트")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportController {

    private final ReportRequestService reportRequestService;
    private final ReportStatusService reportStatusService;

    @Operation(
            summary = "리포트 분석 작업 등록",
            description = """
                    끝난 세션의 분석을 AI 에 맡기고 즉시 202 로 응답합니다.
                    진행률과 결과는 WebSocket /ws/reports/{reportId} 로 받습니다 (progress · report · error).

                    세션이 COMPLETED 여야 합니다. 진행 중이면 409 SESSION_NOT_COMPLETED,
                    중단됐으면 409 SESSION_ABORTED 입니다.
                    이미 요청한 세션은 409 REPORT_ALREADY_EXISTS 입니다. 단 FAILED 는 다시 요청할 수 있고
                    reportId 는 그대로입니다.
                    되묻기를 뺀 답변이 2문항 미만이면 422 REPORT_TOO_SHORT 이며 리포트를 만들지 않습니다.""")
    @PostMapping("/interviews/{sessionId}/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimit(key = "report-request", limit = 10, windowSeconds = 60)
    public Result<ReportRequestResponse> request(@CurrentUser String userId,
                                                 @PathVariable String sessionId) {
        return Result.ok(reportRequestService.request(userId, sessionId));
    }

    @Operation(
            summary = "리포트 생성 상태 조회",
            description = """
                    리포트의 현재 상태를 한 번 조회합니다. WebSocket 에 늦게 붙거나 새로고침해서
                    놓친 완료·실패를 따라잡는 용도입니다. 소켓 연결 직후 1회 부르세요. 폴링용이 아닙니다.

                    PROCESSING 이면 마지막 stage · progress (아직 없으면 null),
                    FAILED 면 errorCode · retryable 이 들어갑니다.
                    COMPLETED · PARTIAL 이면 리포트 조회로 넘어가면 됩니다.

                    본인 리포트가 아니거나 없으면 404 REPORT_NOT_FOUND 입니다.""")
    @GetMapping("/reports/{reportId}/status")
    public Result<ReportStatusResponse> status(@CurrentUser String userId,
                                               @PathVariable String reportId) {
        return Result.ok(reportStatusService.getStatus(userId, reportId));
    }
}
