package com.cuea.infrastructure.websocket.message;

/** 처리 중 진행 상황. type = "progress" */
public record ProgressPushMessage(ProgressStage stage) {

    public static SocketMessage<ProgressPushMessage> of(ProgressStage stage) {
        return SocketMessage.of("progress", new ProgressPushMessage(stage));
    }
}
