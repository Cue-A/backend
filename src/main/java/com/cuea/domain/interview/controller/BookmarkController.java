package com.cuea.domain.interview.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.interview.dto.request.BookmarkToggleRequest;
import com.cuea.domain.interview.dto.response.BookmarkToggleResponse;
import com.cuea.domain.interview.service.BookmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 면접 중 북마크는 면접 경로 아래, 질문은행 화면의 API 는 {@code /api/bookmarks} 아래에 둡니다. */
@Tag(name = "질문은행")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    @Operation(
            summary = "면접 중 북마크 켜기 · 끄기",
            description = """
                    바뀔 상태를 bookmarked 로 보냅니다. 이미 그 상태면 아무것도 바꾸지 않고 성공합니다.
                    false 로 빼면 메모도 함께 지워집니다. 되묻기 질문도 담을 수 있습니다.
                    진행 중인 세션에서만 되며 끝난 세션은 409 SESSION_ENDED, 중단된 세션은 409 SESSION_ABORTED 입니다.""")
    @PutMapping("/interviews/{sessionId}/questions/{questionId}/bookmark")
    @RateLimit(key = "bookmark-toggle", limit = 30, windowSeconds = 60)
    public Result<BookmarkToggleResponse> setBookmarked(
            @CurrentUser String userId,
            @PathVariable String sessionId,
            @PathVariable String questionId,
            @Valid @RequestBody BookmarkToggleRequest request) {

        return Result.ok(bookmarkService.setBookmarked(userId, sessionId, questionId, request.bookmarked()));
    }
}
