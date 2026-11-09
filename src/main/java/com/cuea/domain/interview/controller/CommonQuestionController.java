package com.cuea.domain.interview.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.interview.dto.request.CommonQuestionFavoriteRequest;
import com.cuea.domain.interview.dto.request.CommonQuestionMemoRequest;
import com.cuea.domain.interview.dto.request.CommonQuestionRequest;
import com.cuea.domain.interview.dto.response.CommonQuestionFavoriteResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionListResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionMemoResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionResponse;
import com.cuea.domain.interview.service.CommonQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 질문은행 「공통 질문」 탭. 기본 질문은 모두가 같이 보고, 즐겨찾기 · 메모와 내 질문은 각자의 것입니다.
 * 면접 중 담은 질문(북마크)은 {@link BookmarkController} 입니다.
 */
@Tag(name = "질문은행")
@RestController
@RequestMapping("/api/common-questions")
@RequiredArgsConstructor
public class CommonQuestionController {

    private final CommonQuestionService commonQuestionService;

    @Operation(
            summary = "공통 질문 목록",
            description = """
                    내 질문(최근에 추가한 것부터) 다음에 기본 질문(정해진 순서)이 옵니다. 페이지 없이 한 번에 줍니다.
                    질문마다 내 즐겨찾기(favorite · favoritedAt)와 메모가 붙고, 내가 추가한 질문은 custom=true 입니다.
                    즐겨찾기를 위로 올리는 정렬은 하지 않습니다.""")
    @GetMapping
    @RateLimit(key = "common-question-list", limit = 60, windowSeconds = 60)
    public Result<CommonQuestionListResponse> list(@CurrentUser String userId) {
        return Result.ok(commonQuestionService.list(userId));
    }

    @Operation(
            summary = "공통 질문 즐겨찾기 켜기 · 끄기",
            description = """
                    바뀔 상태를 favorite 로 보냅니다. 이미 그 상태면 아무것도 바꾸지 않고 성공합니다.
                    꺼도 메모는 남습니다. 없는 질문 · 남의 질문이면 404 QUESTION_NOT_FOUND 입니다.""")
    @PutMapping("/{questionId}/favorite")
    @RateLimit(key = "common-question-favorite", limit = 30, windowSeconds = 60)
    public Result<CommonQuestionFavoriteResponse> setFavorite(
            @CurrentUser String userId,
            @PathVariable String questionId,
            @Valid @RequestBody CommonQuestionFavoriteRequest request) {

        return Result.ok(commonQuestionService.setFavorite(userId, questionId, request.favorite()));
    }

    @Operation(
            summary = "공통 질문 메모 쓰기 · 고치기",
            description = """
                    처음 쓰기와 고치기가 같은 API 입니다. memo 는 500자까지이고, 없거나 비었거나 공백뿐이면 메모를 지웁니다.
                    기본 질문에도 됩니다. 없는 질문 · 남의 질문이면 404 QUESTION_NOT_FOUND 입니다.""")
    @PatchMapping("/{questionId}/memo")
    @RateLimit(key = "common-question-memo", limit = 30, windowSeconds = 60)
    public Result<CommonQuestionMemoResponse> editMemo(
            @CurrentUser String userId,
            @PathVariable String questionId,
            @RequestBody CommonQuestionMemoRequest request) {

        return Result.ok(commonQuestionService.editMemo(userId, questionId, request.memo()));
    }

    @Operation(
            summary = "내 질문 추가",
            description = """
                    text 는 앞뒤 공백을 자른 뒤 1~200자, category 는 인성 · 경험 · 지원동기 · 직무 · 기타 중 하나이고
                    어기면 400 INVALID_REQUEST 입니다. 내 질문은 나만 봅니다. 이미 100개면 409 CUSTOM_QUESTION_LIMIT_EXCEEDED 입니다.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit(key = "common-question-create", limit = 30, windowSeconds = 60)
    public Result<CommonQuestionResponse> create(
            @CurrentUser String userId,
            @RequestBody CommonQuestionRequest request) {

        return Result.ok(commonQuestionService.create(userId, request.text(), request.category()));
    }

    @Operation(
            summary = "내 질문 수정",
            description = """
                    문구 · 분류 규칙은 추가와 같습니다. 즐겨찾기 · 메모는 그대로입니다.
                    기본 질문이면 403 FORBIDDEN, 없는 질문 · 남의 질문이면 404 QUESTION_NOT_FOUND 입니다.""")
    @PatchMapping("/{questionId}")
    @RateLimit(key = "common-question-update", limit = 30, windowSeconds = 60)
    public Result<CommonQuestionResponse> update(
            @CurrentUser String userId,
            @PathVariable String questionId,
            @RequestBody CommonQuestionRequest request) {

        return Result.ok(commonQuestionService.update(userId, questionId, request.text(), request.category()));
    }

    @Operation(
            summary = "내 질문 삭제",
            description = """
                    즐겨찾기 · 메모도 함께 지워집니다.
                    기본 질문이면 403 FORBIDDEN, 없는 질문 · 남의 질문 · 이미 지운 질문이면 404 QUESTION_NOT_FOUND 입니다.""")
    @DeleteMapping("/{questionId}")
    @RateLimit(key = "common-question-delete", limit = 30, windowSeconds = 60)
    public Result<Void> delete(
            @CurrentUser String userId,
            @PathVariable String questionId) {

        commonQuestionService.delete(userId, questionId);
        return Result.ok();
    }
}
