package com.cuea.domain.interview.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.interview.dto.request.BookmarkMemoRequest;
import com.cuea.domain.interview.dto.request.BookmarkToggleRequest;
import com.cuea.domain.interview.dto.response.BookmarkListResponse;
import com.cuea.domain.interview.dto.response.BookmarkMemoResponse;
import com.cuea.domain.interview.dto.response.BookmarkToggleResponse;
import com.cuea.domain.interview.service.BookmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @Operation(
            summary = "질문은행 목록",
            description = """
                    내가 담은 질문을 담은 시각 최신순으로 줍니다. 필터는 없고 size 는 100 을 넘길 수 없습니다.
                    originalQuestionText 는 원래 질문 문구입니다. 되묻기는 되물은 질문, 꼬리질문은 그 토픽의 주질문이고
                    주질문이면 null 입니다.""")
    @GetMapping("/bookmarks")
    @RateLimit(key = "bookmark-list", limit = 60, windowSeconds = 60)
    public Result<BookmarkListResponse> list(
            @CurrentUser String userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        return Result.ok(bookmarkService.list(userId, page, size));
    }

    @Operation(
            summary = "질문은행 메모 쓰기 · 고치기",
            description = """
                    처음 쓰기와 고치기가 같은 API 입니다. memo 는 500자까지이고, 없거나 비었거나 공백뿐이면 메모를 지웁니다.
                    면접이 끝난 뒤에도 됩니다. 질문은행에 없거나 남의 것이면 404 BOOKMARK_NOT_FOUND 입니다.""")
    @PatchMapping("/bookmarks/{sessionId}/{questionId}")
    @RateLimit(key = "bookmark-memo", limit = 30, windowSeconds = 60)
    public Result<BookmarkMemoResponse> editMemo(
            @CurrentUser String userId,
            @PathVariable String sessionId,
            @PathVariable String questionId,
            @RequestBody BookmarkMemoRequest request) {

        return Result.ok(bookmarkService.editMemo(userId, sessionId, questionId, request.memo()));
    }

    @Operation(
            summary = "질문은행에서 빼기",
            description = """
                    메모도 함께 지워집니다. 면접 기록과 리포트는 그대로이고, 면접이 끝난 뒤에도 됩니다.
                    질문은행에 없거나 남의 것이거나 이미 지웠으면 404 BOOKMARK_NOT_FOUND 입니다.""")
    @DeleteMapping("/bookmarks/{sessionId}/{questionId}")
    @RateLimit(key = "bookmark-delete", limit = 30, windowSeconds = 60)
    public Result<Void> delete(
            @CurrentUser String userId,
            @PathVariable String sessionId,
            @PathVariable String questionId) {

        bookmarkService.delete(userId, sessionId, questionId);
        return Result.ok();
    }
}
