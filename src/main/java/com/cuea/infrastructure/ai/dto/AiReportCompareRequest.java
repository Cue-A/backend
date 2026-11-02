package com.cuea.infrastructure.ai.dto;

import java.util.List;
import java.util.Map;

/**
 * POST /ai/reports/compare 요청 본문. 계약 15장.
 *
 * <p>AI 는 리포트를 보관하지 않으므로 비교할 회차의 리포트 원본을 전부 실어 보냅니다.
 *
 * @param reports 회차 오름차순
 */
@AiJson
public record AiReportCompareRequest(List<Round> reports) {

    /**
     * @param round  연습 기록의 회차 번호 그대로. {@code [1, 3]} 처럼 비어도 됩니다.
     *               1회차(원래 면접)는 반드시 1 이어야 AI 가 재면접 문항과 짝을 맞춥니다
     * @param report {@code report.report_data} 원본. 키는 이미 snake_case 입니다
     */
    @AiJson
    public record Round(String sessionId, int round, Map<String, Object> report) {
    }
}
