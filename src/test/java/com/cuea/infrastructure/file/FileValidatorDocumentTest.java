package com.cuea.infrastructure.file;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 문서 업로드 검증.
 *
 * <p>여기가 뚫리면 AI 가 못 읽는 파일이 그대로 등록되고, 사용자는 면접을 시작한
 * 뒤에야 실패를 봅니다.
 */
class FileValidatorDocumentTest {

    private static final String PDF = "application/pdf";
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final FileValidator validator = new FileValidator();

    @Test
    void 허용된_형식은_통과하고_확장자를_돌려준다() {
        assertThat(validator.validateDocument("resume.pdf", PDF, 1_024)).isEqualTo("pdf");
        assertThat(validator.validateDocument("resume.docx", DOCX, 1_024)).isEqualTo("docx");
        assertThat(validator.validateDocument("resume.txt", "text/plain", 1_024)).isEqualTo("txt");
    }

    @Test
    void 대문자_확장자도_통과한다() {
        assertThat(validator.validateDocument("RESUME.PDF", PDF, 1_024)).isEqualTo("pdf");
    }

    @Test
    void hwp_는_거절한다() {
        assertThatThrownBy(() -> validator.validateDocument("자소서.hwp", "application/x-hwp", 1_024))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_FILE_FORMAT);
    }

    /**
     * 확장자만 보는 검사는 이걸 통과시킵니다. MIME 만 보는 검사도 마찬가지입니다.
     * 클라이언트가 보낸 {@code Content-Type} 을 그대로 믿지 않는 이유입니다.
     */
    @Test
    void 확장자는_맞는데_MIME_이_다르면_거절한다() {
        assertThatThrownBy(() -> validator.validateDocument("resume.pdf", "application/x-hwp", 1_024))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_FILE_FORMAT);
    }

    @Test
    void 확장자가_없으면_거절한다() {
        assertThatThrownBy(() -> validator.validateDocument("resume", PDF, 1_024))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_FILE_FORMAT);
    }

    /**
     * 브라우저가 {@code text/plain; charset=UTF-8} 로 보냅니다. 파라미터를 떼지
     * 않으면 txt 업로드가 브라우저별로 갈립니다.
     */
    @Test
    void MIME_에_charset_이_붙어도_통과한다() {
        assertThatCode(() -> validator.validateDocument("resume.txt", "text/plain; charset=UTF-8", 1_024))
                .doesNotThrowAnyException();
    }

    @Test
    void 상한을_넘으면_거절한다() {
        long tooBig = FileValidator.DOCUMENT_MAX_BYTES + 1;

        assertThatThrownBy(() -> validator.validateDocument("resume.pdf", PDF, tooBig))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_SIZE_EXCEEDED);
    }

    @Test
    void 정확히_상한이면_통과한다() {
        assertThatCode(() -> validator.validateDocument("resume.pdf", PDF, FileValidator.DOCUMENT_MAX_BYTES))
                .doesNotThrowAnyException();
    }

    /**
     * 메시지를 허용 목록에서 만들어내므로, 형식을 늘리면 안내도 같이 바뀝니다.
     * 목록과 안내가 따로 놀면 "왜 막히는지 모르겠다"는 문의가 그대로 늘어납니다.
     */
    @Test
    void 형식_오류_메시지가_허용_목록을_그대로_알려준다() {
        assertThatThrownBy(() -> validator.validateDocument("자소서.hwp", "application/x-hwp", 1_024))
                .hasMessage("지원하지 않는 파일 형식입니다. pdf, docx, txt만 업로드할 수 있습니다");
    }

    @Test
    void 크기_오류_메시지가_상한을_알려준다() {
        assertThatThrownBy(() -> validator.validateDocument(
                "resume.pdf", PDF, FileValidator.DOCUMENT_MAX_BYTES + 1))
                .hasMessage("파일 크기가 10MB를 초과했습니다");
    }

    /** 녹음도 같은 코드를 쓰지만 상한과 허용 형식은 각자입니다. */
    @Test
    void 녹음은_문서와_다른_상한과_형식을_쓴다() {
        assertThatCode(() -> validator.validateAnswerAudio("answer.webm", "audio/webm;codecs=opus", 20L * 1024 * 1024))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateAnswerAudio("answer.pdf", PDF, 1_024))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_FILE_FORMAT)
                .hasMessage("지원하지 않는 파일 형식입니다. webm, mp4만 업로드할 수 있습니다");
    }

    @Test
    void 빈_파일은_거절한다() {
        assertThatThrownBy(() -> validator.validateDocument("resume.pdf", PDF, 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_SOURCE_TYPE);
    }
}
