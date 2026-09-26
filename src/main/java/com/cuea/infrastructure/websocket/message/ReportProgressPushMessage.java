package com.cuea.infrastructure.websocket.message;

/**
 * 리포트 진행 상황. type = "progress"
 *
 * @param progress 0~1. AI 가 주지 않으면 null
 */
public record ReportProgressPushMessage(ReportProgressStage stage, Double progress) {

    public static SocketMessage<ReportProgressPushMessage> of(ReportProgressStage stage, Double progress) {
        return SocketMessage.of("progress", new ReportProgressPushMessage(stage, progress));
    }
}
