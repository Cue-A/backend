package com.cuea.infrastructure.file;

/**
 * 버킷 안 경로 규칙. docs/20-storage.md 와 같아야 합니다.
 *
 * <pre>
 * resumes/{userId}/{documentId}.{ext}                프론트가 Presigned PUT
 * sessions/{sessionId}/answers/{questionId}.webm     프론트가 Presigned PUT
 * sessions/{sessionId}/questions/{questionId}.mp3    AI 가 직접 PUT
 * </pre>
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

    /** AI 가 쓰는 경로입니다. Spring 은 읽기만 합니다. */
    public static String questionAudio(String sessionId, String questionId) {
        return "sessions/%s/questions/%s.mp3".formatted(sessionId, questionId);
    }
}
