package com.cuea.infrastructure.file;

import org.springframework.core.io.InputStreamSource;

/**
 * 업로드된 파일 하나를 저장소에 넘기기 위한 운반 객체.
 *
 * <p>컨트롤러가 {@code MultipartFile} 을 여기로 옮겨 담습니다. 도메인 서비스가
 * 웹 계층 타입({@code MultipartFile})도 저장소 타입(S3 SDK)도 보지 않게 하려는
 * 것입니다. 나중에 Presigned 방식으로 되돌리더라도 도메인은 그대로 둡니다.
 *
 * <p><b>열린 스트림이 아니라 {@link InputStreamSource} 를 담습니다.</b> 열린 스트림을
 * 담으면 검증에서 거부된 파일의 스트림이 읽히지도, 닫히지도 않은 채 버려집니다.
 * {@code .hwp} 를 올릴 때마다 파일 디스크립터가 하나씩 새는 셈입니다. 여기서는
 * "열 수 있는 것" 만 들고 다니고, 실제로 여는 것은 소비하는 저장소 구현입니다.
 *
 * <p>{@code InputStreamSource} 는 spring-core 타입이고 {@code MultipartFile} 이
 * 이를 구현합니다. 덕분에 컨트롤러는 파일을 그대로 넘기면 되고, 서비스는 여전히
 * 웹 타입을 보지 않습니다.
 *
 * @param size 바이트 수. S3 는 스트림을 받을 때 길이를 미리 알아야 해서 함께 넘깁니다
 */
public record UploadedFile(
        String fileName,
        String contentType,
        long size,
        InputStreamSource content
) {
}
