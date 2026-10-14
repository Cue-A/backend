package com.cuea.infrastructure.ai.dto;

import java.util.List;

/**
 * POST /ai/sessions/{sessionId}/report/retry 요청 본문. 리포트 계약 14장.
 *
 * <p>리포트 생성 본문({@link AiReportRequest})에 다시 계산할 축({@code axes})을 더한 것입니다.
 * presigned URL 은 생성 때 것을 쓰지 않고 새로 발급한 본문을 넘기세요.
 *
 * @param axes 부분 리포트의 {@code overall.axes_failed} 그대로. 비어 있으면 AI 가 400 입니다
 */
@AiJson
public record AiReportRetryRequest(
        List<String> axes,
        String persona,
        String jobRole,
        String companyId,
        String companyProfileOverride,
        List<AiReportAnswer> answers
) {

    public static AiReportRetryRequest of(List<String> axes, AiReportRequest base) {
        return new AiReportRetryRequest(axes, base.persona(), base.jobRole(), base.companyId(),
                base.companyProfileOverride(), base.answers());
    }
}
