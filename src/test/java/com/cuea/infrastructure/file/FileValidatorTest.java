package com.cuea.infrastructure.file;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileValidatorTest {

    private final FileValidator validator = new FileValidator();

    private static final long ONE_MB = 1024L * 1024L;

    @Test
    @DisplayName("MediaRecorder 가 보내는 codecs 파라미터가 붙은 audio/webm 도 통과한다")
    void codecs_파라미터가_붙은_audio_webm_통과() {
        assertThatCode(() -> validator.validateAnswerAudio(
                "answer.webm", "audio/webm;codecs=opus", 20L * ONE_MB))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("파라미터 앞뒤 공백이 있어도 base MIME 으로 비교한다")
    void 공백_포함_파라미터도_통과() {
        assertThatCode(() -> validator.validateAnswerAudio(
                "answer.webm", "audio/webm; codecs=opus", 20L * ONE_MB))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("파라미터 없는 정확한 MIME 도 여전히 통과한다")
    void 파라미터_없는_MIME_통과() {
        assertThatCode(() -> validator.validateAnswerAudio(
                "answer.mp4", "audio/mp4", 20L * ONE_MB))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("영상 answer 는 codecs 파라미터가 붙은 video/webm 도 통과한다")
    void 영상_codecs_파라미터_통과() {
        assertThatCode(() -> validator.validateAnswerVideo(
                "answer_video.webm", "video/webm;codecs=vp8,opus", 20L * ONE_MB))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("확장자와 base MIME 타입이 맞지 않으면 거부한다")
    void 확장자와_MIME_불일치_거부() {
        assertThatThrownBy(() -> validator.validateAnswerAudio(
                "answer.webm", "audio/mp4;codecs=mp4a", 20L * ONE_MB))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("오디오 자리에 video MIME 이 오면 거부한다")
    void 오디오_자리에_video_MIME_거부() {
        assertThatThrownBy(() -> validator.validateAnswerAudio(
                "answer.webm", "video/webm;codecs=vp8", 20L * ONE_MB))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("허용되지 않은 확장자는 거부한다")
    void 허용되지_않은_확장자_거부() {
        assertThatThrownBy(() -> validator.validateAnswerAudio(
                "answer.mp3", "audio/mpeg", 20L * ONE_MB))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("크기가 상한을 넘으면 거부한다")
    void 크기_초과_거부() {
        assertThatThrownBy(() -> validator.validateAnswerAudio(
                "answer.webm", "audio/webm;codecs=opus", 60L * ONE_MB))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    @DisplayName("extensionOf 는 소문자 확장자를 돌려준다")
    void extensionOf_소문자() {
        assertThat(validator.extensionOf("Answer.WEBM")).isEqualTo("webm");
    }
}
