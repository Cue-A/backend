package com.cuea.domain.interview.dto.request;

import com.cuea.domain.interview.entity.Persona;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/interviews 요청 본문.
 *
 * @param documentId 면접 자료로 쓸 문서의 식별자(UUID 문자열). 값은 문서의
 *                  {@code public_id} 이며, 외부 API 필드명은 문서 API와 맞춰
 *                  {@code documentId} 로 통일했습니다. 필수(Issue #23 부터
 *                  {@code session.document_id} 가 NOT NULL 입니다).
 * @param companyId        회사를 선택했으면 그 회사의 {@code company_id}. 미선택 연습
 *                         모드면 null.
 * @param jobRole          자유 문자열. VARCHAR(100) 기준.
 * @param persona          FRIENDLY | PRESSURE
 * @param questionCount    3 | 6 | 9. null 이면 서비스 기본값(6)을 씁니다.
 */
public record InterviewStartRequest(
        @NotBlank
        String documentId,

        Long companyId,

        @NotBlank
        @Size(max = 100)
        String jobRole,

        @NotNull
        Persona persona,

        Integer questionCount
) {
}
