package com.cuea.domain.interview.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.interview.dto.request.FolderNameRequest;
import com.cuea.domain.interview.dto.response.FolderListResponse;
import com.cuea.domain.interview.dto.response.FolderResponse;
import com.cuea.domain.interview.service.FolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "연습 기록 폴더")
@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;

    @Operation(
            summary = "폴더 목록",
            description = """
                    연습 기록 화면의 폴더 카드입니다. 만든 순서대로 나옵니다.
                    totalCount 는 「전체」 카드의 세션 수이고, 세션 수는 연습 기록 목록과 같은 기준입니다
                    (회차마다 하나, 중단된 세션 제외).""")
    @GetMapping
    @RateLimit(key = "folder-list", limit = 60, windowSeconds = 60)
    public Result<FolderListResponse> list(@CurrentUser String userId) {
        return Result.ok(folderService.list(userId));
    }

    @Operation(
            summary = "폴더 만들기",
            description = """
                    folderName 은 앞뒤 공백을 자른 뒤 1~50자입니다.
                    내 폴더끼리 같은 이름이면 409 FOLDER_NAME_DUPLICATED, 이미 20개면 409 FOLDER_LIMIT_EXCEEDED 입니다.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit(key = "folder-create", limit = 30, windowSeconds = 60)
    public Result<FolderResponse> create(
            @CurrentUser String userId,
            @RequestBody FolderNameRequest request) {

        return Result.ok(folderService.create(userId, request.folderName()));
    }

    @Operation(
            summary = "폴더 이름 바꾸기",
            description = """
                    이름 규칙은 만들기와 같습니다. 지금 이름과 같으면 그대로 성공합니다.
                    없는 폴더 · 남의 폴더 · UUID 가 아닌 값은 404 입니다.""")
    @PatchMapping("/{folderId}")
    @RateLimit(key = "folder-update", limit = 30, windowSeconds = 60)
    public Result<FolderResponse> rename(
            @CurrentUser String userId,
            @PathVariable String folderId,
            @RequestBody FolderNameRequest request) {

        return Result.ok(folderService.rename(userId, folderId, request.folderName()));
    }

    @Operation(
            summary = "폴더 삭제",
            description = """
                    안의 세션은 지우지 않고 폴더에서만 빠집니다. 「전체」에는 그대로 보입니다.
                    없는 폴더 · 남의 폴더 · 이미 지운 폴더는 404 입니다.""")
    @DeleteMapping("/{folderId}")
    @RateLimit(key = "folder-delete", limit = 30, windowSeconds = 60)
    public Result<Void> delete(
            @CurrentUser String userId,
            @PathVariable String folderId) {

        folderService.delete(userId, folderId);
        return Result.ok();
    }
}
