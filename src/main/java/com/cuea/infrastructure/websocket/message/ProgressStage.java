package com.cuea.infrastructure.websocket.message;

import java.util.Locale;

/**
 * 프론트에 보내는 진행 단계.
 *
 * <p>AI 의 stage 값("stt" | "generating" | "tts")을 그대로 내보내면 AI 내부
 * 파이프라인 이름이 프론트 코드에 박힙니다. AI 가 파이프라인을 바꾸면 프론트가
 * 깨집니다. 반드시 이 enum 으로 옮겨서 보냅니다.
 */
public enum ProgressStage {

    TRANSCRIBING,   // stt
    GENERATING,     // generating
    SYNTHESIZING;   // tts

    public static ProgressStage from(String aiStage) {
        if (aiStage == null) {
            return GENERATING;
        }
        return switch (aiStage.toLowerCase(Locale.ROOT)) {
            case "stt" -> TRANSCRIBING;
            case "tts" -> SYNTHESIZING;
            default -> GENERATING;
        };
    }
}
