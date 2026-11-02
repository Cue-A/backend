package com.cuea.domain.interview.dto.response;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 재연습용 세션 옵션 조회 응답. {@code GET /api/interviews/{sessionId}/replay-options}.
 *
 * <p><b>항상 최초(root) 세션의 옵션을 담습니다.</b> 2·3회차 세션 ID 로 조회해도 그
 * 회차가 아니라 최초 1회차의 값이 들어갑니다. 재연습은 최초 세션과 같은 조건으로만
 * 시작할 수 있으므로(docs/12-replay.md 옵션 고정 정책), 프론트는 이 응답의 고정 옵션
 * 5개({@code documentId}·{@code companyId}·{@code jobRole}·{@code questionCount}·
 * {@code persona})를 그대로 {@code POST /api/interviews} 요청에 실어야
 * {@code ReplayOptionPolicy} 의 비교를 통과합니다.
 *
 * <p>직렬화 형식은 재연습 시작 요청({@link com.cuea.domain.interview.dto.request.InterviewStartRequest})
 * 과 맞춥니다.
 * <ul>
 *   <li>{@code documentId} 는 문서의 {@code publicId}(UUID) 문자열입니다. 내부 PK 를
 *       노출하지 않습니다. 세션의 {@code document} 는 NOT NULL 이라 항상 값이 있습니다.</li>
 *   <li>{@code companyId} 는 기업의 BIGINT PK({@code Long})입니다. 회사를 고르지 않은
 *       연습 모드 세션이면 {@code null} 입니다(정상 상태).</li>
 *   <li>{@code persona} 는 {@code FRIENDLY}/{@code PRESSURE} enum 이름 그대로입니다.
 *       (AI 요청용 소문자 변환은 이 조회에서 쓰지 않습니다 — AI 서버를 호출하지 않습니다.)</li>
 *   <li>{@code questionCount} 는 DB 에 저장된 정규화 값(3·6·9)입니다.</li>
 * </ul>
 *
 * <p>표시용 {@code documentTitle}·{@code companyName} 은 root 세션의 연관 엔티티에서
 * 읽습니다. 기업 미선택이면 {@code companyName} 도 {@code null} 입니다.
 *
 * <p>타이머·{@code hideQuestionText}·질문/답변·리포트는 담지 않습니다. 타이머(답변 제한
 * 시간)는 세션마다 {@code duration_limit} 에 저장되지만 재연습에서 변경 가능한 옵션이라
 * 고정 옵션이 아니고, {@code hideQuestionText} 도 재연습에서 변경 가능해 고정 옵션이
 * 아닙니다. 현재 세션의 타이머 값이 필요하면 {@code GET /api/interviews/{sessionId}} 를 쓰세요.
 *
 * <h3>재연습 가능 여부 (Issue #83 리뷰 반영)</h3>
 * <p>{@code replayable}·{@code replayBlockReasons} 는 "지금 이 조건으로 재연습 시작 API 를
 * 호출하면 통과하는가"를 <b>미리 알려주는 참고 정보</b>입니다. 조회 자체는 여전히 막지
 * 않습니다(삭제 문서·미검증 기업이어도 200). 최종 판정은 재연습 시작 API 가 합니다
 * (docs/12-replay.md "조회와 재연습 시작 가능 여부의 분리").
 * <ul>
 *   <li>{@code replayable=true}, {@code replayBlockReasons=[]} — 유효한 문서·기업. 바로 시작 가능</li>
 *   <li>{@code replayable=false} — root 에 연결된 문서가 삭제됐거나({@link ReplayBlockReason#DOCUMENT_DELETED})
 *       기업이 미검증({@link ReplayBlockReason#COMPANY_UNVERIFIED})이라, 시작 API 가 거부함.
 *       두 조건이 겹치면 {@code replayBlockReasons} 에 둘 다 담김</li>
 * </ul>
 * <p>재연습 옵션은 사용자가 바꿀 수 없으므로, 프론트는 이 값으로 경고 문구를 띄우거나
 * 시작 버튼을 비활성화합니다. 다른 문서·기업으로 교체하도록 유도하지 않습니다.
 *
 * <p>판정은 {@code root} 세션에 이미 fetch join 으로 로딩된 {@code document}·{@code company}
 * 로만 하며 <b>추가 DB 조회가 없습니다</b>(N+1 없음).
 */
@Schema(description = "재연습용 세션 옵션. 항상 최초(root) 세션 값입니다")
public record ReplaySessionOptionsResponse(

        @Schema(description = "최초 root 세션 ID. 전달한 회차와 무관하게 최초 세션입니다",
                example = "sess_1st_abc")
        String rootSessionId,

        @Schema(description = "이력서 문서 식별자(UUID). 재연습 시작 요청에 그대로 씁니다",
                example = "3f1a0c2e-7b2d-4f6a-9c11-2e4b6d8a0f3c")
        String documentId,

        @Schema(description = "기업 식별자. 기업을 고르지 않은 연습 모드면 null",
                nullable = true, example = "12")
        Long companyId,

        @Schema(description = "직무", example = "백엔드 개발")
        String jobRole,

        @Schema(description = "문항 수(되묻기 제외). 3·6·9 중 하나", example = "6")
        int questionCount,

        @Schema(description = "면접관 스타일", example = "PRESSURE")
        Persona persona,

        @Schema(description = "이력서 제목(표시용)", example = "홍길동 자기소개서")
        String documentTitle,

        @Schema(description = "기업명(표시용). 기업 미선택이면 null",
                nullable = true, example = "현대엔지니어링")
        String companyName,

        @Schema(description = "지금 이 조건으로 재연습을 시작할 수 있는지. 최종 판정은 시작 API 가 함",
                example = "true")
        boolean replayable,

        @Schema(description = "재연습을 시작할 수 없는 사유 목록. 시작 가능하면 빈 배열")
        List<ReplayBlockReasonItem> replayBlockReasons
) {

    /**
     * 재연습 불가 사유 한 건. 안정적인 {@code code}(프론트 분기용)와 사용자 안내
     * {@code message}(표시용)를 함께 내려 줍니다.
     */
    @Schema(description = "재연습 불가 사유 한 건")
    public record ReplayBlockReasonItem(

            @Schema(description = "사유 코드(프론트 분기용)", example = "DOCUMENT_DELETED")
            ReplayBlockReason code,

            @Schema(description = "사용자 안내 문구(표시용)",
                    example = "최초 면접에 사용한 이력서가 삭제되어 재연습을 시작할 수 없습니다")
            String message
    ) {
        static ReplayBlockReasonItem of(ReplayBlockReason reason) {
            return new ReplayBlockReasonItem(reason, reason.getMessage());
        }
    }

    /**
     * 최초 root 세션에서 응답을 만듭니다. {@code document}·{@code company} 연관은 호출 전에
     * ({@code InterviewSessionRepository.findRootForReplay} 의 fetch join 으로) 초기화돼 있어야
     * 합니다. 그렇지 않으면 트랜잭션 밖(open-in-view=false)에서 {@code LazyInitializationException}
     * 이 납니다.
     *
     * <p>{@code replayable}·{@code replayBlockReasons} 는 이미 로딩된 {@code document}·
     * {@code company} 로만 판정합니다(추가 쿼리 없음). 판정 기준은 재연습 시작 API 의 거부
     * 조건과 일치합니다({@link ReplayBlockReason}).
     */
    public static ReplaySessionOptionsResponse from(InterviewSession rootSession) {
        Document document = rootSession.getDocument();
        Company company = rootSession.getCompany();

        List<ReplayBlockReasonItem> blockReasons = resolveBlockReasons(document, company);

        return new ReplaySessionOptionsResponse(
                rootSession.getSessionId(),
                document.getPublicId().toString(),
                company == null ? null : company.getCompanyId(),
                rootSession.getJobRole(),
                rootSession.getQuestionCount(),
                rootSession.getPersona(),
                document.getDocTitle(),
                company == null ? null : company.getCompanyName(),
                blockReasons.isEmpty(),
                blockReasons);
    }

    /**
     * 재연습 시작 API 가 거부할 조건을 root 의 연관으로 미리 판정합니다. 시작 API 의
     * {@code findUsableFileDocument}(삭제 문서 → {@code DOCUMENT_NOT_FOUND})와
     * {@code findVerifiedCompany}({@code verified=false} → {@code INVALID_REQUEST})에
     * 각각 대응합니다. 시작 API 에 없는 거부는 만들지 않습니다.
     */
    private static List<ReplayBlockReasonItem> resolveBlockReasons(Document document, Company company) {
        List<ReplayBlockReasonItem> reasons = new ArrayList<>(2);
        if (document.isDeleted()) {
            reasons.add(ReplayBlockReasonItem.of(ReplayBlockReason.DOCUMENT_DELETED));
        }
        if (company != null && !company.isVerified()) {
            reasons.add(ReplayBlockReasonItem.of(ReplayBlockReason.COMPANY_UNVERIFIED));
        }
        return reasons;
    }
}
