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
 * @param hideQuestionText 질문 텍스트를 숨기고 음성만 들려주는 실전 모드면 true.
 *                         null 이면 기본값 텍스트+음성(false)으로 저장합니다.
 * @param answerTimeLimitSec 질문 1개당 답변 제한 시간(초). 허용값은 {@code 60}·{@code 90}·
 *                         {@code 120}·{@code null} 이며, 그 외 값은 {@code INVALID_REQUEST}(400)로
 *                         거부합니다(판정은 {@code InterviewStartService}). {@code null} 은 제한 없음을
 *                         뜻하며 DB {@code duration_limit} 에 null 로 저장합니다. 필드를 보내지 않은
 *                         경우(필드 누락)와 명시적 {@code null} 은 Jackson 역직렬화상 구별되지 않아
 *                         <b>둘 다 "제한 없음(null)"</b>으로 처리합니다. 백엔드는 기본값(90초)을
 *                         적용하지 않으며, 기본값은 프론트 UI 선택값으로 요청에 실려 옵니다.
 *                         {@code hideQuestionText} 와 같은 관례(누락/null 미구분)를 따릅니다.
 * @param sourceSessionId  재면접(replay) 대상 세션의 식별자. null 이면 일반 면접입니다.
 *                         값이 있으면 그 세션의 최초 root 세션을 기준으로 재면접을
 *                         구성합니다. 식별자 타입은 기존 세션 ID 와 같은 문자열입니다.
 *                         고정 옵션(이력서·기업·직무·문항 수·면접관 스타일)이 최초 세션과
 *                         다르면 {@code INVALID_REQUEST}(400)로 거부합니다(일반 면접으로
 *                         자동 전환하지 않습니다). 판정은 {@code InterviewStartService} 가
 *                         합니다. docs/12-replay.md 참고.
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

        Integer questionCount,

        Boolean hideQuestionText,

        Integer answerTimeLimitSec,

        String sourceSessionId
) {

    /** 재면접 요청인지. sourceSessionId 가 비어 있지 않으면 재면접입니다. */
    public boolean isReplayRequested() {
        return sourceSessionId != null && !sourceSessionId.isBlank();
    }
}
