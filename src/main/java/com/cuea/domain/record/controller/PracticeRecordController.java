package com.cuea.domain.record.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.interview.dto.response.FolderMoveResponse;
import com.cuea.domain.interview.service.FolderService;
import com.cuea.domain.record.dto.request.PracticeRecordFolderRequest;
import com.cuea.domain.record.dto.request.PracticeRecordStatus;
import com.cuea.domain.record.dto.response.PracticeRecordListResponse;
import com.cuea.domain.record.service.PracticeRecordQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "연습 기록")
@RestController
@RequestMapping("/api/practice-records")
@RequiredArgsConstructor
public class PracticeRecordController {

    private final PracticeRecordQueryService practiceRecordQueryService;
    private final FolderService folderService;

    @Operation(
            summary = "연습 기록 목록",
            description = """
                    본인 면접 세션 목록입니다. 최신순이고 중단된 세션은 나오지 않습니다.
                    행마다 리포트 요약(report)이 붙고, 리포트를 요청하지 않았으면 null 입니다.
                    status · folderId · keyword(회사명 · 직무)로 거를 수 있고 size 는 100 을 넘길 수 없습니다.""")
    @GetMapping
    @RateLimit(key = "practice-records", limit = 60, windowSeconds = 60)
    public Result<PracticeRecordListResponse> list(
            @CurrentUser String userId,
            @RequestParam(required = false) PracticeRecordStatus status,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        return Result.ok(practiceRecordQueryService.list(userId, status, folderId, keyword, page, size));
    }

    @Operation(
            summary = "연습 기록 폴더 옮기기",
            description = """
                    sessionIds 를 folderId 폴더로 옮깁니다. folderId 가 null 이면 폴더에서 뺍니다.
                    면접 단위로 움직여, 보낸 회차가 속한 면접의 1회차와 재면접이 모두 옮겨집니다.
                    sessionIds 는 1~100개이고 중복은 무시합니다. 하나라도 없거나 남의 것이거나 중단된 세션이면
                    아무것도 옮기지 않고 404 SESSION_NOT_FOUND, 폴더가 없거나 남의 것이면 404 FOLDER_NOT_FOUND 입니다.""")
    @PatchMapping("/folder")
    @RateLimit(key = "practice-records-folder", limit = 30, windowSeconds = 60)
    public Result<FolderMoveResponse> moveToFolder(
            @CurrentUser String userId,
            @RequestBody PracticeRecordFolderRequest request) {

        return Result.ok(folderService.moveSessions(userId, request.sessionIds(), request.folderId()));
    }
}
