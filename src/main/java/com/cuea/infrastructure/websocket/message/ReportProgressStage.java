package com.cuea.infrastructure.websocket.message;

import java.util.Locale;

/**
 * 리포트 생성 진행 단계. 프론트에 보내는 값입니다.
 *
 * <p>면접용 {@link ProgressStage} 와 따로 둡니다. AI 리포트 작업의 stage 값이 질문 생성과
 * 전혀 다르고, 프론트 로딩 문구도 다릅니다. AI 값을 그대로 내보내지 않는 이유는
 * {@link ProgressStage} 와 같습니다.
 */
public enum ReportProgressStage {

    TRANSCRIBING,        // transcribing
    ANALYZING_SPEECH,    // analyzing_speech
    ANALYZING_GAZE,      // analyzing_gaze
    ANALYZING_CONTENT,   // analyzing_content
    COMPOSING;           // composing

    /** 모르는 값은 null. 폴러가 그 단계는 알리지 않고 넘어갑니다. */
    public static ReportProgressStage from(String aiStage) {
        if (aiStage == null) {
            return null;
        }
        try {
            return valueOf(aiStage.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
