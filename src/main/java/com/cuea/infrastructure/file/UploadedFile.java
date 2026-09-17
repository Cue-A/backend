package com.cuea.infrastructure.file;

import java.io.InputStream;

/**
 * 업로드된 파일 하나를 저장소에 넘기기 위한 운반 객체.
 *
 * <p>컨트롤러가 {@code MultipartFile} 을 여기로 옮겨 담습니다. 도메인 서비스가
 * 웹 계층 타입({@code MultipartFile})도 저장소 타입(S3 SDK)도 보지 않게 하려는
 * 것입니다. 나중에 Presigned 방식으로 되돌리더라도 도메인은 그대로 둡니다.
 *
 * <p><b>{@code content} 는 한 번만 읽을 수 있습니다.</b> 저장소 구현이 소비하므로
 * 도메인에서 미리 읽지 마세요.
 *
 * @param size 바이트 수. S3 는 스트림을 받을 때 길이를 미리 알아야 해서 함께 넘깁니다
 */
public record UploadedFile(
        String fileName,
        String contentType,
        long size,
        InputStream content
) {
}
