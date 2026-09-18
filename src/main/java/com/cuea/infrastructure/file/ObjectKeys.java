package com.cuea.infrastructure.file;

/**
 * 버킷 안 경로 규칙. docs/20-storage.md 와 같아야 합니다.
 *
 * <pre>
 * resumes/{userId}/{documentId}.{ext}                프론트가 Presigned PUT
 * sessions/{sessionId}/answers/{questionId}.{ext}        프론트가 Presigned PUT (audio)
 * sessions/{sessionId}/answers/{questionId}_video.{ext}  프론트가 Presigned PUT (video)
 * sessions/{sessionId}/questions/{questionId}.mp3    AI 가 직접 PUT
 * </pre>
 *
 * <p>답변 오디오·영상은 같은 {@code sessions/{sessionId}/answers/{questionId}} 계층을
 * 쓰되 확장자로 구분합니다. 포맷은 계약에서 고정하지 않아(webm·mp4 허용,
 * docs/20-storage.md) 실제 업로드 확장자를 그대로 씁니다. content-type 과
 * 확장자가 어긋나지 않도록 발급 시 {@code FileValidator} 로 함께 검증합니다.
 */
public final class ObjectKeys {

    private ObjectKeys() {
    }

    public static String resume(String userId, String documentId, String extension) {
        return "resumes/%s/%s.%s".formatted(userId, documentId, extension);
    }

    public static String answerAudio(String sessionId, String questionId, String extension) {
        return "sessions/%s/answers/%s.%s".formatted(sessionId, questionId, extension);
    }

    /**
     * 답변 영상 key. audio 와 같은 {@code answers/} 계층을 쓰되 파일명에 {@code _video}
     * 를 붙여 audio 와 확실히 분리합니다. audio·video 확장자가 같을 수 있어(둘 다 webm
     * 가능) 확장자만으로는 key 가 겹치기 때문입니다. 카메라 미사용이면 애초에 발급하지
     * 않습니다. 포맷은 실제 업로드 확장자를 그대로 씁니다.
     */
    public static String answerVideo(String sessionId, String questionId, String extension) {
        return "sessions/%s/answers/%s_video.%s".formatted(sessionId, questionId, extension);
    }

    /** AI 가 쓰는 경로입니다. Spring 은 읽기만 합니다. */
    public static String questionAudio(String sessionId, String questionId) {
        return "sessions/%s/questions/%s.mp3".formatted(sessionId, questionId);
    }

    /** 한 질문의 답변 미디어가 놓이는 접두사. {@code sessions/{sessionId}/answers/{questionId}} */
    private static String answerBase(String sessionId, String questionId) {
        return "sessions/%s/answers/%s".formatted(sessionId, questionId);
    }

    /**
     * 프론트가 되돌려준 답변 <b>오디오</b> object key 가 해당 세션·질문의 정규
     * namespace 에 속하는지 검증합니다.
     *
     * <p>업로드 URL 발급 시 서버가 만든 key 규칙과 정확히 일치해야 통과합니다. 이를
     * 통해 프론트가 다른 세션·다른 질문·임의 경로의 key 를 보내 presigned GET 을
     * 얻는 것을 막습니다. 허용 형태는 {@code sessions/{sessionId}/answers/{questionId}.{ext}}
     * ({@code ext} 는 {@code webm}|{@code mp4}) 뿐입니다.
     *
     * @return 검증에 성공하면 그 key 그대로. 형식이 어긋나면 {@code false} 판정용으로 쓰세요.
     */
    public static boolean isValidAnswerAudioKey(String sessionId, String questionId, String key) {
        if (key == null) {
            return false;
        }
        String base = answerBase(sessionId, questionId);
        return key.equals(base + ".webm") || key.equals(base + ".mp4");
    }

    /**
     * 프론트가 되돌려준 답변 <b>영상</b> object key 가 해당 세션·질문의 정규
     * namespace 에 속하는지 검증합니다. 허용 형태는
     * {@code sessions/{sessionId}/answers/{questionId}_video.{ext}}
     * ({@code ext} 는 {@code webm}|{@code mp4}) 뿐입니다.
     */
    public static boolean isValidAnswerVideoKey(String sessionId, String questionId, String key) {
        if (key == null) {
            return false;
        }
        String base = answerBase(sessionId, questionId) + "_video";
        return key.equals(base + ".webm") || key.equals(base + ".mp4");
    }
}
