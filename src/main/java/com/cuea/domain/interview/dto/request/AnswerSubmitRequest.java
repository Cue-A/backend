package com.cuea.domain.interview.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/interviews/{sessionId}/answers 요청 본문.
 *
 * <p>프론트는 {@code upload-urls} 로 받은 object key 를 그대로 실어 보냅니다. 서버는
 * 이 key 를 질문에 저장하고, AI 호출 직전에 presigned GET URL 로 바꿔 전달합니다.
 * <b>presigned URL 을 직접 받지 않습니다.</b> URL 은 만료되고, 서버가 key 소유·경로를
 * 통제해야 하기 때문입니다.
 *
 * @param questionId      어느 질문에 대한 답변인지. 세션 스코프 ID
 * @param audioObjectKey  답변 오디오 object key. 필수
 * @param videoObjectKey  답변 영상 object key. 카메라 미사용이면 null
 * @param isTimeout       제한 시간 만료로 자동 제출된 답변인지. <b>계약상 필수</b>라
 *                        {@code Boolean} + {@code @NotNull} 로 두어 JSON 누락과 false 를
 *                        구분합니다. primitive {@code boolean} 이면 누락 시 Jackson 이
 *                        조용히 false 로 채워 계약 위반을 놓칩니다(되묻기 동작을 좌우).
 */
public record AnswerSubmitRequest(
        @NotBlank String questionId,
        @NotBlank String audioObjectKey,
        String videoObjectKey,
        @NotNull Boolean isTimeout
) {
}
