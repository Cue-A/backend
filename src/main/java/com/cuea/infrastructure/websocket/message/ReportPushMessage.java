package com.cuea.infrastructure.websocket.message;

/**
 * 리포트 생성 완료. type = "report"
 *
 * @param status     COMPLETED | PARTIAL
 * @param scoreTotal 0~100
 */
public record ReportPushMessage(String reportId, String status, Integer scoreTotal) {

    public static SocketMessage<ReportPushMessage> of(ReportPushMessage payload) {
        return SocketMessage.of("report", payload);
    }
}
