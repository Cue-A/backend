package com.cuea.infrastructure.file;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 업로드 전 검증.
 *
 * <p>MIME 타입은 클라이언트가 보낸 값이라 그대로 믿지 않고 확장자와 함께 봅니다.
 */
@Component
public class FileValidator {

    private static final long RESUME_MAX_BYTES = 10L * 1024 * 1024;   // 10MB
    private static final long AUDIO_MAX_BYTES = 50L * 1024 * 1024;    // 50MB

    private static final Map<String, Set<String>> RESUME_TYPES = Map.of(
            "pdf", Set.of("application/pdf"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "doc", Set.of("application/msword"),
            "txt", Set.of("text/plain"));

    private static final Map<String, Set<String>> AUDIO_TYPES = Map.of(
            "webm", Set.of("audio/webm", "video/webm"),
            "mp4", Set.of("audio/mp4", "video/mp4"));

    public void validateResume(String fileName, String mimeType, long size) {
        validate(fileName, mimeType, size, RESUME_TYPES, RESUME_MAX_BYTES);
    }

    public void validateAnswerAudio(String fileName, String mimeType, long size) {
        validate(fileName, mimeType, size, AUDIO_TYPES, AUDIO_MAX_BYTES);
    }

    public String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "확장자가 없는 파일입니다");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void validate(String fileName, String mimeType, long size,
                          Map<String, Set<String>> allowed, long maxBytes) {
        if (size <= 0 || size > maxBytes) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "파일 크기는 %dMB 이하여야 합니다".formatted(maxBytes / 1024 / 1024));
        }
        String extension = extensionOf(fileName);
        Set<String> mimeTypes = allowed.get(extension);
        if (mimeTypes == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "허용되지 않은 확장자입니다: " + extension);
        }
        if (mimeType == null || !mimeTypes.contains(mimeType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "확장자와 MIME 타입이 맞지 않습니다");
        }
    }
}
