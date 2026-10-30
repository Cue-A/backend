package com.cuea.domain.interview.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.interview.dto.request.AnswerSubmitRequest;
import com.cuea.domain.interview.dto.request.AnswerUploadUrlRequest;
import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.dto.response.AnswerUploadUrlResponse;
import com.cuea.domain.interview.dto.response.InterviewStartResponse;
import com.cuea.domain.interview.dto.response.ReplaySessionOptionsResponse;
import com.cuea.domain.interview.service.InterviewAnswerService;
import com.cuea.domain.interview.service.InterviewStartService;
import com.cuea.domain.interview.service.ReplaySessionOptionsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 면접 세션 시작 및 답변 제출.
 *
 * <p>세션 시작·답변 제출 모두 AI 호출은 {@code task_id} 즉시 반환 + 폴링 모델이라,
 * REST 는 폴링을 <b>기다리지 않고</b> 반환합니다. 결과(다음 질문·되묻기·세션 종료)는
 * 백그라운드 폴링 후 {@code /ws/interviews/{sessionId}} 로 push 되므로, 프론트는
 * 소켓 연결을 유지해야 합니다.
 */
@Tag(name = "면접")
@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewStartService interviewStartService;
    private final InterviewAnswerService interviewAnswerService;
    private final ReplaySessionOptionsService replaySessionOptionsService;

    @Operation(summary = "면접 세션 시작",
            description = "Document/Company 조회 → AI 세션 생성(session_id·task_id) 까지 동기로 처리하고 "
                    + "즉시 202 로 응답합니다. 첫 질문은 백그라운드 폴링 후 WebSocket 으로 전달됩니다. "
                    + "문서는 status=READY 여야 하고(파일·마크다운 모두 가능), 기업을 선택했다면 verified=true 여야 합니다. "
                    + "sourceSessionId 를 주면 그 세션의 최초 세션을 기준으로 재면접(replay)을 시작합니다. "
                    + "재면접은 최초 세션과 같은 조건으로만 가능하며, 문항 수·페르소나·이력서·기업·직무가 하나라도 "
                    + "다르면 400 으로 거부합니다(일반 면접으로 자동 전환하지 않습니다). 질문 텍스트 숨김·타이머는 "
                    + "변경할 수 있습니다. 정상 재면접이면 응답의 replayApplied=true, 일반 면접이면 false 입니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimit(key = "session-start", limit = 10, windowSeconds = 60)
    public Result<InterviewStartResponse> start(@CurrentUser String userId,
                                                 @Valid @RequestBody InterviewStartRequest request) {
        return Result.ok(interviewStartService.start(userId, request));
    }

    @Operation(summary = "재연습용 세션 옵션 조회",
            description = "재연습을 시작하기 전에 최초(root) 세션의 고정 옵션을 조회합니다. "
                    + "Authorization: Bearer <accessToken> 필요. "
                    + "경로의 sessionId 에는 재연습하려는 1·2·3회차 등 어느 회차의 세션 ID 를 넣어도 되며, "
                    + "응답은 항상 그 묶음의 최초 세션 옵션입니다(2·3회차 ID 로 조회해도 1회차 값 반환). "
                    + "AI 서버를 호출하지 않고 저장된 세션 정보만 읽습니다. "
                    + "응답의 고정 옵션 5개(documentId·companyId·jobRole·questionCount·persona)를 그대로 "
                    + "POST /api/interviews 에 실으면 재연습 옵션 비교를 통과합니다. "
                    + "존재하지 않거나 다른 사용자의 세션이면 404(SESSION_NOT_FOUND), 완료되지 않은 세션이면 "
                    + "409(SESSION_NOT_COMPLETED) 입니다. 기업을 고르지 않은 연습 모드 세션은 companyId·companyName 이 "
                    + "null 인 정상 응답입니다. 삭제된 문서·미검증 기업이 연결돼 있어도 조회는 저장된 옵션을 그대로 "
                    + "반환하며, 실제 재연습 시작 가능 여부는 시작 API 가 판정합니다.")
    @GetMapping("/{sessionId}/replay-options")
    @RateLimit(key = "replay-options", limit = 60, windowSeconds = 60)
    public Result<ReplaySessionOptionsResponse> getReplayOptions(@CurrentUser String userId,
                                                                 @PathVariable String sessionId) {
        return Result.ok(replaySessionOptionsService.getReplayOptions(userId, sessionId));
    }

    @Operation(summary = "답변 업로드 URL 발급",
            description = "답변 오디오(필수)·영상(선택)의 presigned PUT URL 을 함께 발급합니다. "
                    + "프론트가 이 URL 로 S3 에 직접 업로드한 뒤, 반환된 object key 를 답변 제출에 실어 보냅니다. "
                    + "URL 은 만료되므로 DB 에 저장하지 않고 object key 만 왕복시킵니다.")
    @PostMapping("/{sessionId}/answers/upload-urls")
    @RateLimit(key = "answer-upload-url", limit = 60, windowSeconds = 60)
    public Result<AnswerUploadUrlResponse> issueAnswerUploadUrls(
            @CurrentUser String userId,
            @PathVariable String sessionId,
            @Valid @RequestBody AnswerUploadUrlRequest request) {
        return Result.ok(interviewAnswerService.issueUploadUrls(userId, sessionId, request));
    }

    @Operation(summary = "답변 제출",
            description = "업로드한 답변 object key 를 저장하고 AI 에 제출(POST /ai/sessions/{id}/answers) 까지 "
                    + "동기로 처리한 뒤 즉시 202 로 응답합니다. 다음 질문·되묻기·세션 종료는 백그라운드 폴링(최대 60초) "
                    + "후 WebSocket 으로 전달됩니다. video object key 는 카메라 미사용 시 생략합니다.")
    @PostMapping("/{sessionId}/answers")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimit(key = "answer-submit", limit = 30, windowSeconds = 60)
    public Result<Void> submitAnswer(@CurrentUser String userId,
                                     @PathVariable String sessionId,
                                     @Valid @RequestBody AnswerSubmitRequest request) {
        interviewAnswerService.submit(userId, sessionId, request);
        return Result.ok();
    }

    @Operation(summary = "면접 세션 중단",
            description = "사용자가 진행 중인 면접을 중단합니다. AI 세션(POST /ai/sessions/{id}/abort)을 정리하고 "
                    + "Backend 세션을 ABORTED 로 전이합니다. 이미 중단된 세션이면 멱등하게 처리되고, 완료된 세션은 "
                    + "중단할 수 없습니다. AI 중단 호출이 실패해도 Backend 세션 정리는 수행합니다.")
    @PostMapping("/{sessionId}/abort")
    @RateLimit(key = "session-abort", limit = 30, windowSeconds = 60)
    public Result<Void> abort(@CurrentUser String userId,
                              @PathVariable String sessionId) {
        interviewAnswerService.abort(userId, sessionId);
        return Result.ok();
    }
}
