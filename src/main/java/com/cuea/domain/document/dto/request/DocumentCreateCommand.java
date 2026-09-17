package com.cuea.domain.document.dto.request;

import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.SourceType;
import com.cuea.infrastructure.file.UploadedFile;

/**
 * 문서 등록 요청을 서비스로 넘기는 형태.
 *
 * <p>컨트롤러가 {@code multipart/form-data} 를 여기로 옮겨 담습니다. 서비스가
 * {@code MultipartFile} 을 직접 받지 않게 하려는 것입니다. 웹 계층 타입이
 * 서비스 시그니처에 들어가면 테스트에서 서블릿 목을 만들어야 합니다.
 *
 * <p>{@code sourceType} 에 따라 둘 중 하나만 채워집니다. {@code FILE} 이면
 * {@code file}, {@code MARKDOWN} 이면 {@code content} 입니다. 둘 다 nullable 인
 * 이유가 이것이고, 어느 쪽이 비었는지는 서비스가 검사합니다.
 */
public record DocumentCreateCommand(
        SourceType sourceType,
        DocType documentType,
        String title,
        String content,
        UploadedFile file
) {
}
