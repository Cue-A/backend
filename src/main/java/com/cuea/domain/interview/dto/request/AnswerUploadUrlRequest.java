package com.cuea.domain.interview.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * POST /api/interviews/{sessionId}/answers/upload-urls 요청 본문.
 *
 * <p>답변 오디오(필수)와 영상(선택, 카메라 미사용이면 생략)의 presigned PUT URL 을
 * 함께 발급받기 위한 파일 메타데이터입니다. object key 확장자와 실제 content-type 이
 * 어긋나지 않도록, 파일명·MIME·크기를 {@code FileValidator} 로 함께 검증합니다.
 *
 * @param questionId    어느 질문에 대한 답변인지. 세션 스코프 ID(예: {@code "q_1"})
 * @param audioFileName 답변 오디오 파일명(확장자 포함). 필수
 * @param audioMimeType 답변 오디오 MIME. 확장자와 함께 검증. 필수
 * @param audioSize     답변 오디오 바이트 크기. 필수
 * @param videoFileName 답변 영상 파일명(확장자 포함). 카메라 미사용이면 null
 * @param videoMimeType 답변 영상 MIME. 영상이 있을 때만
 * @param videoSize     답변 영상 바이트 크기. 영상이 있을 때만
 */
public record AnswerUploadUrlRequest(
        @NotBlank String questionId,
        @NotBlank String audioFileName,
        @NotBlank String audioMimeType,
        @Positive long audioSize,
        String videoFileName,
        String videoMimeType,
        Long videoSize
) {

    /** 카메라를 썼는지. 영상 파일명이 있으면 영상 URL 도 발급합니다. */
    public boolean hasVideo() {
        return videoFileName != null && !videoFileName.isBlank();
    }
}
