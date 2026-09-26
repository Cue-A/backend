package com.cuea.infrastructure.ai.dto;

import java.util.List;

/**
 * POST /ai/sessions/{sessionId}/report 요청 본문.
 *
 * <p>AI 는 세션 로그를 보관하지 않으므로 채점에 필요한 것을 전부 실어 보냅니다.
 * 필드는 리포트 계약 2장과 1:1 입니다.
 *
 * @param companyId              회사 PK 문자열. 미선택이면 null. 면접 시작 때 보낸 값과 같습니다
 * @param companyProfileOverride 인재상 텍스트. 면접 시작 때 보낸 값과 같습니다. 미선택이면 null
 * @param answers                세션에서 나간 질문과 답변 전체. 되묻기 포함, 나간 순서대로
 */
@AiJson
public record AiReportRequest(
        String persona,
        String jobRole,
        String companyId,
        String companyProfileOverride,
        List<AiReportAnswer> answers
) {
}
