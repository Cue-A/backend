package com.cuea.domain.document.controller;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.document.dto.request.DocumentCreateCommand;
import com.cuea.domain.document.dto.response.DocumentResponse;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.SourceType;
import com.cuea.domain.document.service.DocumentRegisterService;
import com.cuea.infrastructure.file.UploadedFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;


/**
 * 문서 API.
 *
 * <p>등록은 {@code multipart/form-data} 하나로 받습니다. 파일 업로드와 마크다운
 * 직접 작성이 사용자에게는 같은 행동("자소서를 올렸다")이라 엔드포인트를 나누지
 * 않았습니다. 판단 근거는 Issue #28 참고.
 */
@Tag(name = "문서")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentRegisterService documentRegisterService;

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
