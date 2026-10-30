package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ReplayOptionPolicy} 고정 옵션 검증 규칙.
 *
 * <p>팀 확정 정책: 문항 수·면접관 스타일·이력서·기업·직무가 최초 세션과 하나라도 다르면
 * {@code INVALID_REQUEST}(400)로 거부(일반 면접으로 전환하지 않음). hideQuestionText·타이머는
 * 비교하지 않음. 비교 입력은 {@link InterviewStartService} 가 조회·정규화한 값이라, 이 테스트는
 * 조회된 {@link Document}/{@link Company} 객체와 정규화된 int 를 직접 넘긴다.
 */
class ReplayOptionPolicyTest {

    private static final UUID DOC_PUBLIC_ID = UUID.randomUUID();

    private final ReplayOptionPolicy policy = new ReplayOptionPolicy();

    private InterviewSession root(Persona persona, int questionCount, String jobRole,
                                  UUID docPublicId, Long companyId) {
        Document document = mock(Document.class);
        when(document.getPublicId()).thenReturn(docPublicId);

        InterviewSession.InterviewSessionBuilder<?, ?> builder = InterviewSession.builder()
                .sessionId("sess_root")
                .document(document)
                .mode("PRACTICE")
                .jobRole(jobRole)
                .questionCount(questionCount)
                .persona(persona)
                .hideQuestionText(false)
                .status(SessionStatus.COMPLETED);
        if (companyId != null) {
            Company company = mock(Company.class);
            when(company.getCompanyId()).thenReturn(companyId);
            builder.company(company);
        }
        return builder.build();
    }

    private Document document(UUID publicId) {
        Document document = mock(Document.class);
        when(document.getPublicId()).thenReturn(publicId);
        return document;
    }

    private Company company(Long companyId) {
        Company company = mock(Company.class);
        when(company.getCompanyId()).thenReturn(companyId);
        return company;
    }

    private InterviewStartRequest request(String documentId, Long companyId, String jobRole,
                                          Persona persona, Integer questionCount,
                                          Boolean hideQuestionText) {
        return new InterviewStartRequest(
                documentId, companyId, jobRole, persona, questionCount, hideQuestionText, "sess_root");
    }

    @Test
    void 모든_고정_옵션이_같으면_통과한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, 10L);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), 10L, "백엔드", Persona.PRESSURE, 6, false);

        assertThatCode(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), company(10L), root))
                .doesNotThrowAnyException();
    }

    @Test
    void questionCount_가_다르면_INVALID_REQUEST_로_거부한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.PRESSURE, 9, false);

        assertThatThrownBy(() -> policy.enforceFixedOptions(req, 9, document(DOC_PUBLIC_ID), null, root))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 정규화된_questionCount_가_같으면_통과한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.PRESSURE, null, false);

        // 서비스가 null→6 으로 정규화해 넘기면 root(6)과 같아 통과.
        assertThatCode(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), null, root))
                .doesNotThrowAnyException();
    }

    @Test
    void persona_가_다르면_INVALID_REQUEST_로_거부한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.FRIENDLY, 6, false);

        assertThatThrownBy(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), null, root))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void documentId_가_다르면_INVALID_REQUEST_로_거부한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        UUID other = UUID.randomUUID();
        InterviewStartRequest req = request(other.toString(), null, "백엔드", Persona.PRESSURE, 6, false);

        assertThatThrownBy(() -> policy.enforceFixedOptions(req, 6, document(other), null, root))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 같은_문서면_요청_UUID_가_대문자여도_통과한다() {
        // C1 회귀: 요청 documentId 가 대문자여도 조회된 Document.publicId 는 같은 UUID 라
        // UUID.equals 로 같다고 판정돼 통과해야 한다(문자열 비교였다면 대소문자로 깨짐).
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        String upper = DOC_PUBLIC_ID.toString().toUpperCase();
        InterviewStartRequest req = request(upper, null, "백엔드", Persona.PRESSURE, 6, false);

        assertThatCode(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), null, root))
                .doesNotThrowAnyException();
    }

    @Test
    void 같은_문서면_요청_UUID_가_소문자여도_통과한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        String lower = DOC_PUBLIC_ID.toString().toLowerCase();
        InterviewStartRequest req = request(lower, null, "백엔드", Persona.PRESSURE, 6, false);

        assertThatCode(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), null, root))
                .doesNotThrowAnyException();
    }

    @Test
    void companyId_가_다르면_INVALID_REQUEST_로_거부한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, 10L);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), 20L, "백엔드", Persona.PRESSURE, 6, false);

        assertThatThrownBy(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), company(20L), root))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void companyId_가_둘_다_null_이면_통과한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.PRESSURE, 6, false);

        assertThatCode(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), null, root))
                .doesNotThrowAnyException();
    }

    @Test
    void 회사를_새로_선택하면_INVALID_REQUEST_로_거부한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), 10L, "백엔드", Persona.PRESSURE, 6, false);

        assertThatThrownBy(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), company(10L), root))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void jobRole_이_다르면_INVALID_REQUEST_로_거부한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "프론트엔드", Persona.PRESSURE, 6, false);

        assertThatThrownBy(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), null, root))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void hideQuestionText_만_다르면_통과한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        // root 는 hideQuestionText=false, 요청은 true 지만 비교 대상이 아니라 통과.
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.PRESSURE, 6, true);

        assertThatCode(() -> policy.enforceFixedOptions(req, 6, document(DOC_PUBLIC_ID), null, root))
                .doesNotThrowAnyException();
    }
}
