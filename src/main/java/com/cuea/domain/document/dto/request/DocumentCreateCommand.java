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

    /**
     * {@code documentType} 을 안 보내면 {@link DocType#RESUME} 입니다.
     *
     * <p>이 값은 면접 동작에 쓰이지 않습니다. 목록을 종류별로 거르는 데만 쓰여서,
     * 등록할 때 반드시 고르게 할 이유가 없습니다. 게다가 <b>지금은 수정·삭제
     * API 가 없어</b> 잘못 고르면 되돌릴 수 없습니다. 애매하면 안 보내도 되게 두고,
     * 화면에 종류 선택이 생기면 그때 값을 채워 보내면 됩니다.
     *
     * <p>기본값을 컨트롤러가 아니라 여기 둔 이유는, 서비스를 직접 부르는 테스트나
     * 다른 진입점에서도 같은 값이 되게 하기 위해서입니다.
     */
    public DocumentCreateCommand {
        documentType = documentType == null ? DocType.RESUME : documentType;
    }
}
