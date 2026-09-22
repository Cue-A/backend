package com.cuea.infrastructure.ai.dto;

/**
 * POST /ai/sessions/{sessionId}/answers 요청 본문.
 *
 * <p>답변 오디오·영상은 프론트가 S3 에 직접 올리고, 우리는 <b>AI 접근용 presigned
 * GET URL</b> 만 넘깁니다. DB 에는 URL 이 아니라 object key 만 저장하고, 이 URL 은
 * AI 호출 직전에 만듭니다. docs/20-storage.md 참고.
 *
 * <p>필드는 AI 최종 계약과 1:1 입니다({@code @AiJson} 이 snake_case 로 직렬화).
 *
 * <pre>
 * question_id  필수. 어느 질문에 대한 답인지
 * audio_url    필수. 백엔드가 저장한 답변 오디오의 presigned GET URL
 * video_url    선택. 답변 영상의 presigned GET URL. 카메라 미사용이면 null
 * is_timeout   필수. 제한 시간 만료로 자동 제출된 답변인가
 * </pre>
 *
 * <p>{@code videoUrl} 은 null 을 허용하며(카메라 미사용), 계약이 명시적으로 null 을
 * 허용하므로 키를 빼지 않고 {@code "video_url": null} 로 보냅니다. AI 는 영상을 질문
 * 진행에 쓰지 않고 리포트 시선 축에서만 씁니다.
 */
@AiJson
public record AiAnswerSubmitRequest(
        String questionId,
        String audioUrl,
        String videoUrl,
        boolean isTimeout
) {
}
