package com.cuea.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * POST /ai/sessions
 *
 * <p>토픽 수는 보내지 않습니다. question_count 에서 자동 결정됩니다.
 *
 * @param resumeFileUrl          Presigned GET URL (만료 15분)
 * @param persona                friendly | pressure
 * @param companyId              Backend Company 의 BIGINT PK 를 문자열로 변환한 값
 *                               (회사 미선택이면 null). AI 는 기업 조회 key 가 아니라
 *                               로그·문제 추적용 opaque ID 로만 씁니다. 질문 생성용 기업
 *                               정보는 {@code companyProfileOverride} 로 전달합니다.
 * @param companyProfileOverride 선택된 기업의 인재상을 AI 계약 형식 문자열로 조립한 값.
 *                               기업을 골랐으면 반드시 채웁니다. {@code CompanyProfileFormatter} 참고.
 * @param retryOfSessionId       재연습이면 최초 세션 ID
 * @param replayLog              재연습이면 필수
 * @param docId                  예약 필드. 이력서 RAG 인덱스 재사용 키. 지금은 항상 null.
 */
@AiJson
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiSessionStartRequest(
        String resumeFileUrl,
        String jobRole,
        String persona,
        String companyId,
        String companyProfileOverride,
        Integer questionCount,
        String retryOfSessionId,
        List<AiReplayLogItem> replayLog,
        String docId
) {
}
