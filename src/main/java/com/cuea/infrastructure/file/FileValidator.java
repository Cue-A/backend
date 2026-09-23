package com.cuea.infrastructure.file;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 업로드 전 검증.
 *
 * <p>검사는 셋입니다. <b>크기</b>, <b>확장자</b>, 그리고 <b>확장자와 MIME 이
 * 같은 형식을 가리키는지</b>입니다. 셋째가 핵심입니다. {@code mimeType} 은
 * 클라이언트가 보낸 값이라 그대로 믿을 수 없고, 확장자는 사용자가 바꿔 붙일 수
 * 있습니다. 한쪽만 보는 검사는 둘 다 뚫립니다.
 *
 * <p>허용 목록만 다르고 규칙은 같아서 {@link #validate} 하나를 넷이 나눠 씁니다.
 * 메시지도 목록에서 뽑아내므로, 형식을 늘릴 때 목록만 고치면 사용자에게 나가는
 * 안내까지 같이 바뀝니다.
 */
@Component
public class FileValidator {

    public static final long DOCUMENT_MAX_BYTES = 10L * 1024 * 1024;  // 10MB
    private static final long RESUME_MAX_BYTES = 10L * 1024 * 1024;   // 10MB
    private static final long AUDIO_MAX_BYTES = 50L * 1024 * 1024;    // 50MB
    private static final long VIDEO_MAX_BYTES = 50L * 1024 * 1024;    // 50MB

    /**
     * 문서 업로드 허용 형식. {@code FileFormat} enum 과 같은 목록이어야 합니다.
     *
     * <p>{@code .hwp} 는 여기에 없어서 막힙니다. {@code .doc}(구 워드)도 뺐습니다.
     * 파싱 품질이 들쭉날쭉한데 사용자는 "올라갔으니 되겠지" 하고 면접까지
     * 진행해버립니다. 받지 않는 편이 낫습니다.
     *
     * <p>안내 메시지에 순서 그대로 들어가므로 {@link LinkedHashMap} 입니다.
     */
    private static final Map<String, Set<String>> DOCUMENT_TYPES = allowed(
            "pdf", Set.of("application/pdf"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "txt", Set.of("text/plain"));

    private static final Map<String, Set<String>> RESUME_TYPES = allowed(
            "pdf", Set.of("application/pdf"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "doc", Set.of("application/msword"),
            "txt", Set.of("text/plain"));

    // docs/20-storage.md: 답변 오디오는 webm, mp4. 오디오 MIME 만 허용합니다.
    private static final Map<String, Set<String>> AUDIO_TYPES = allowed(
            "webm", Set.of("audio/webm"),
            "mp4", Set.of("audio/mp4"));

    // 답변 영상도 같은 컨테이너(webm, mp4)를 쓰되 영상 MIME 만 허용합니다.
    // docs/20-storage.md 의 답변 미디어 허용 포맷(webm, mp4) 안에서 audio↔video 를
    // MIME 로 구분할 뿐, 새 포맷을 추가하지 않습니다.
    private static final Map<String, Set<String>> VIDEO_TYPES = allowed(
            "webm", Set.of("video/webm"),
            "mp4", Set.of("video/mp4"));

    /**
     * 문서 업로드 검증. 통과하면 소문자 확장자를 돌려줍니다.
     *
     * <p>빈 파일을 크기 초과와 다른 코드로 가르는 이유는, 사용자가 할 일이
     * 다르기 때문입니다. 0바이트는 대개 파일을 잘못 고른 것이고, 크기 초과는
     * 파일을 줄여야 하는 것입니다.
     */
    public String validateDocument(String fileName, String mimeType, long size) {
        if (size <= 0) {
            throw new BusinessException(ErrorCode.INVALID_SOURCE_TYPE, "빈 파일은 등록할 수 없습니다");
        }
        return validate(fileName, mimeType, size, DOCUMENT_TYPES, DOCUMENT_MAX_BYTES);
    }

    public String validateResume(String fileName, String mimeType, long size) {
        return validate(fileName, mimeType, size, RESUME_TYPES, RESUME_MAX_BYTES);
    }

    public String validateAnswerAudio(String fileName, String mimeType, long size) {
        return validate(fileName, mimeType, size, AUDIO_TYPES, AUDIO_MAX_BYTES);
    }

    /**
     * 답변 영상 검증. 확장자는 오디오와 같은 webm·mp4 이지만 MIME 은 {@code video/*} 만
     * 허용해, 오디오 파일이 영상 자리에 올라오는 것을 막습니다. docs/20-storage.md 기준.
     */
    public String validateAnswerVideo(String fileName, String mimeType, long size) {
        return validate(fileName, mimeType, size, VIDEO_TYPES, VIDEO_MAX_BYTES);
    }

    public String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_FORMAT, "확장자가 없는 파일입니다");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** @return 소문자 확장자 */
    private String validate(String fileName, String mimeType, long size,
                            Map<String, Set<String>> allowed, long maxBytes) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_FORMAT, "파일명이 없습니다");
        }
        if (size <= 0 || size > maxBytes) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED,
                    "파일 크기가 %dMB를 초과했습니다".formatted(maxBytes / 1024 / 1024));
        }

        String extension = extensionOf(fileName);
        Set<String> mimeTypes = allowed.get(extension);
        if (mimeTypes == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_FORMAT,
                    "지원하지 않는 파일 형식입니다. %s만 업로드할 수 있습니다"
                            .formatted(String.join(", ", allowed.keySet())));
        }
        if (mimeType == null || !mimeTypes.contains(baseMimeType(mimeType))) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_FORMAT,
                    "확장자와 MIME 타입이 맞지 않습니다");
        }
        return extension;
    }

    /**
     * {@code text/plain; charset=UTF-8} 이나 {@code audio/webm;codecs=opus} 처럼
     * 파라미터가 붙어 옵니다. 떼지 않으면 같은 파일이 브라우저에 따라 통과하기도
     * 하고 막히기도 합니다.
     */
    private String baseMimeType(String mimeType) {
        int semicolon = mimeType.indexOf(';');
        String base = semicolon < 0 ? mimeType : mimeType.substring(0, semicolon);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 안내 메시지가 목록 순서를 그대로 쓰므로 순서를 지키는 맵으로 모읍니다.
     * {@code Map.of} 는 순서를 보장하지 않아 "pdf, docx, txt" 가 매번 뒤바뀝니다.
     */
    private static Map<String, Set<String>> allowed(Object... pairs) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            Set<String> mimeTypes = (Set<String>) pairs[i + 1];
            map.put((String) pairs[i], mimeTypes);
        }
        return Collections.unmodifiableMap(map);
    }
}
