package com.cuea.domain.interview.dto.response;

/**
 * POST /api/interviews/{sessionId}/answers/upload-urls 응답.
 *
 * <p>프론트는 이 PUT URL 로 S3 에 직접 업로드한 뒤, 함께 받은 object key 를
 * 답변 제출({@code POST /api/interviews/{sessionId}/answers}) 에 그대로 실어 보냅니다.
 * URL 은 만료되므로 DB 에 저장하지 않고 key 만 왕복시킵니다.
 *
 * @param audioObjectKey 답변 오디오 object key. 제출 시 그대로 돌려보냅니다
 * @param audioUploadUrl 답변 오디오 presigned PUT URL
 * @param videoObjectKey 답변 영상 object key. 카메라 미사용이면 null
 * @param videoUploadUrl 답변 영상 presigned PUT URL. 카메라 미사용이면 null
 */
public record AnswerUploadUrlResponse(
        String audioObjectKey,
        String audioUploadUrl,
        String videoObjectKey,
        String videoUploadUrl
) {
}
