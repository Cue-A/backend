package com.cuea.domain.interview.controller;

import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.dto.response.InterviewStartResponse;
import com.cuea.domain.interview.service.InterviewStartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 면접 세션 시작.
 *
 * <p>이 엔드포인트는 AI 세션 생성(session_id·task_id 수신)까지만 하고 <b>즉시
 * 응답</b>합니다. 첫 질문은 백그라운드 폴링 후 {@code /ws/interviews/{sessionId}} 로
 * push 되므로, 프론트는 응답을 받는 즉시 WebSocket 에 연결해야 합니다.
 *
 * <p>답변 제출·꼬리질문·되묻기·세션 종료는 이번 PR 범위가 아닙니다. Issue #23 참고.
 */
@Tag(name = "면접")
@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewStartService interviewStartService;

    @Operation(summary = "면접 세션 시작",
            description = "Document/Company 조회 → AI 세션 생성(session_id·task_id) 까지 동기로 처리하고 "
                    + "즉시 응답합니다. 첫 질문은 백그라운드 폴링 후 WebSocket 으로 전달됩니다. "
                    + "문서는 status=READY 여야 하고, 기업을 선택했다면 verified=true 여야 합니다.")
    @PostMapping
    public Result<InterviewStartResponse> start(@CurrentUser String userId,
                                                 @Valid @RequestBody InterviewStartRequest request) {
        return Result.ok(interviewStartService.start(userId, request));
    }
}
