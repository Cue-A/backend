package com.cuea.domain.interview.service;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ReplayOptionPolicy} 옵션 비교 규칙 검증.
 *
 * <ul>
 *   <li>questionCount(정규화된 값)·persona·documentId·companyId·jobRole 중 하나라도 바뀌면 일반 면접</li>
 *   <li>hideQuestionText 는 비교하지 않음(재면접 유지)</li>
 *   <li>문서는 조회된 Document 의 publicId(UUID)로 비교 — 요청 문자열의 대소문자에 영향받지 않음</li>
 * </ul>
 *
 * <p>비교 입력은 {@link InterviewStartService} 가 이미 조회·정규화한 값입니다. 따라서 이
 * 테스트는 조회된 {@link Document}/{@link Company} 객체와 정규화된 int 를 직접 넘깁니다.
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

    /** 조회된 Document mock. publicId 는 UUID 타입으로 돌려준다. */
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
    void 모든_옵션이_같으면_재면접을_유지한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, 10L);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), 10L, "백엔드", Persona.PRESSURE, 6, false);

        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), company(10L), root)).isTrue();
    }

    @Test
    void questionCount_가_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.PRESSURE, 9, false);

        // 서비스가 9 로 정규화해 넘긴다.
        assertThat(policy.keepsReplay(req, 9, document(DOC_PUBLIC_ID), null, root)).isFalse();
    }

    @Test
    void 정규화된_questionCount_가_같으면_재면접을_유지한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.PRESSURE, null, false);

        // 서비스가 null→6 으로 정규화해 넘기면 root(6)과 같아 유지.
        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), null, root)).isTrue();
    }

    @Test
    void persona_가_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.FRIENDLY, 6, false);

        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), null, root)).isFalse();
    }

    @Test
    void documentId_가_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        UUID other = UUID.randomUUID();
        InterviewStartRequest req = request(other.toString(), null, "백엔드", Persona.PRESSURE, 6, false);

        assertThat(policy.keepsReplay(req, 6, document(other), null, root)).isFalse();
    }

    @Test
    void 같은_문서면_요청_UUID_가_대문자여도_재면접을_유지한다() {
        // C1 회귀: 요청 documentId 가 대문자여도, 조회된 Document.publicId 는 같은 UUID 이므로
        // UUID.equals 로 같다고 판정돼야 한다(문자열 비교였다면 대소문자 차이로 깨짐).
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        String upper = DOC_PUBLIC_ID.toString().toUpperCase();
        InterviewStartRequest req = request(upper, null, "백엔드", Persona.PRESSURE, 6, false);

        // 조회는 UUID.fromString 으로 대소문자 무시 → 같은 Document(publicId=DOC_PUBLIC_ID).
        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), null, root)).isTrue();
    }

    @Test
    void 같은_문서면_요청_UUID_가_소문자여도_재면접을_유지한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        String lower = DOC_PUBLIC_ID.toString().toLowerCase();
        InterviewStartRequest req = request(lower, null, "백엔드", Persona.PRESSURE, 6, false);

        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), null, root)).isTrue();
    }

    @Test
    void companyId_가_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, 10L);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), 20L, "백엔드", Persona.PRESSURE, 6, false);

        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), company(20L), root)).isFalse();
    }

    @Test
    void companyId_가_둘_다_null_이면_같다고_본다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.PRESSURE, 6, false);

        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), null, root)).isTrue();
    }

    @Test
    void 회사를_새로_선택하면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), 10L, "백엔드", Persona.PRESSURE, 6, false);

        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), company(10L), root)).isFalse();
    }

    @Test
    void jobRole_이_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "프론트엔드", Persona.PRESSURE, 6, false);

        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), null, root)).isFalse();
    }

    @Test
    void hideQuestionText_만_바뀌면_재면접을_유지한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        // root 는 hideQuestionText=false, 요청은 true 지만 비교 대상이 아니라 유지.
        InterviewStartRequest req = request(DOC_PUBLIC_ID.toString(), null, "백엔드", Persona.PRESSURE, 6, true);

        assertThat(policy.keepsReplay(req, 6, document(DOC_PUBLIC_ID), null, root)).isTrue();
    }
}
