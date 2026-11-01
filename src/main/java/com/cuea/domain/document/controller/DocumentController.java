package com.cuea.domain.document.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.document.dto.request.DocumentCreateCommand;
import com.cuea.domain.document.dto.request.DocumentUpdateRequest;
import com.cuea.domain.document.dto.response.DocumentDetailResponse;
import com.cuea.domain.document.dto.response.DocumentListResponse;
import com.cuea.domain.document.dto.response.DocumentResponse;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.SourceType;
import com.cuea.domain.document.service.DocumentDeleteService;
import com.cuea.domain.document.service.DocumentQueryService;
import com.cuea.domain.document.service.DocumentRegisterService;
import com.cuea.domain.document.service.DocumentUpdateService;
import com.cuea.infrastructure.file.UploadedFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;


/**
 * 문서 API. <b>전부 본인 문서만 다룹니다.</b>
 *
 * <p>등록은 {@code multipart/form-data} 하나로 받습니다. 파일 업로드와 마크다운
 * 직접 작성이 사용자에게는 같은 행동("자소서를 올렸다")이라 엔드포인트를 나누지
 * 않았습니다. 판단 근거는 Issue #28 참고.
 *
 * <p>인덱싱 상태는 내보내지 않습니다. 인덱싱은 면접 세션을 만들 때 AI 서버가
 * 하므로 등록한 문서는 곧바로 면접에 쓸 수 있습니다(Issue #57).
 */
@Tag(name = "문서")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentRegisterService documentRegisterService;
    private final DocumentQueryService documentQueryService;
    private final DocumentUpdateService documentUpdateService;
    private final DocumentDeleteService documentDeleteService;

    @Operation(
            summary = "문서 등록",
            description = """
                    파일 업로드와 마크다운 직접 작성을 한 엔드포인트로 처리합니다.

                    sourceType=FILE 이면 file, MARKDOWN 이면 content 가 필요합니다.

                    documentType 은 선택입니다. 안 보내면 RESUME 입니다.
                    파일은 pdf · docx · txt 만, 최대 10MB 입니다.""")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit(key = "document-create", limit = 20, windowSeconds = 60)
    public Result<DocumentResponse> create(
            @CurrentUser String userId,
            @RequestParam SourceType sourceType,
            @RequestParam(required = false) DocType documentType,
            @RequestParam String title,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) MultipartFile file) {

        DocumentCreateCommand command = new DocumentCreateCommand(
                sourceType, documentType, title, content, toUploadedFile(file));

        return Result.ok(documentRegisterService.register(userId, command));
    }

    @Operation(
            summary = "문서 목록",
            description = """
                    본인 문서만 보입니다. 최신순입니다.

                    documentType 을 주면 그 종류만, 없으면 전체입니다.
                    size 는 100 을 넘길 수 없습니다. 비어 있어도 200 입니다.""")
    @GetMapping
    public Result<DocumentListResponse> list(
            @CurrentUser String userId,
            @RequestParam(required = false) DocType documentType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        return Result.ok(documentQueryService.list(userId, documentType, page, size));
    }

    @Operation(
            summary = "문서 상세",
            description = """
                    본인 문서만 보입니다. 남의 문서도 404 입니다.

                    sourceType=MARKDOWN 이면 content 에 본문이, FILE 이면
                    downloadUrl 에 1시간짜리 Presigned URL 이 담깁니다.""")
    @GetMapping("/{documentId}")
    public Result<DocumentDetailResponse> detail(
            @CurrentUser String userId,
            @PathVariable String documentId) {

        return Result.ok(documentQueryService.detail(userId, documentId));
    }

    @Operation(
            summary = "문서 제목 수정",
            description = """
                    제목만 바꿀 수 있습니다. 본문 수정은 지원하지 않습니다.

                    title 은 앞뒤 공백을 자른 뒤 1~100자입니다.
                    본인 문서만 수정할 수 있고, 남의 문서·없는 문서·지운 문서는 404 입니다.
                    응답은 목록·상세의 문서 객체와 같은 형태입니다.""")
    @PatchMapping("/{documentId}")
    @RateLimit(key = "document-update", limit = 30, windowSeconds = 60)
    public Result<DocumentResponse> updateTitle(
            @CurrentUser String userId,
            @PathVariable String documentId,
            @RequestBody DocumentUpdateRequest request) {

        return Result.ok(documentUpdateService.updateTitle(userId, documentId, request.title()));
    }

    @Operation(
            summary = "문서 삭제",
            description = """
                    본인 문서만 지울 수 있습니다. 남의 문서·없는 문서·이미 지운 문서는 404 입니다.

                    지운 문서는 목록·상세에서 사라지고 20개 상한에서도 빠집니다.
                    이 문서로 봤던 과거 면접 기록은 그대로 남습니다.""")
    @DeleteMapping("/{documentId}")
    @RateLimit(key = "document-delete", limit = 30, windowSeconds = 60)
    public Result<Void> delete(
            @CurrentUser String userId,
            @PathVariable String documentId) {

        documentDeleteService.delete(userId, documentId);
        return Result.ok();
    }

    /**
     * 웹 계층 타입을 저장소가 아는 형태로 옮깁니다.
     *
     * <p><b>스트림을 여기서 열지 않습니다.</b> {@code MultipartFile} 이
     * {@code InputStreamSource} 를 구현하므로 파일 자체를 넘기고, 여는 것은 실제로
     * 소비하는 저장소 구현이 합니다. 여기서 열면 검증에서 거부된 파일의 스트림이
     * 읽히지도 닫히지도 않은 채 버려집니다.
     */
    private UploadedFile toUploadedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        return new UploadedFile(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file);
    }
}
