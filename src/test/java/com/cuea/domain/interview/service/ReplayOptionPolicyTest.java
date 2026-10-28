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
 *   <li>questionCount·persona·documentId·companyId·jobRole 중 하나라도 바뀌면 일반 면접</li>
 *   <li>hideQuestionText 는 비교하지 않음(재면접 유지)</li>
 *   <li>questionCount null 은 기본 6 으로 정규화 후 비교</li>
 * </ul>
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

    private InterviewStartRequest request(Long companyId, String jobRole, Persona persona,
                                          Integer questionCount, Boolean hideQuestionText,
                                          UUID docPublicId) {
        return new InterviewStartRequest(
                docPublicId.toString(), companyId, jobRole, persona, questionCount,
                hideQuestionText, "sess_root");
    }

    @Test
    void 모든_옵션이_같으면_재면접을_유지한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, 10L);
        InterviewStartRequest req = request(10L, "백엔드", Persona.PRESSURE, 6, false, DOC_PUBLIC_ID);

        assertThat(policy.keepsReplay(req, root)).isTrue();
    }

    @Test
    void questionCount_가_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(null, "백엔드", Persona.PRESSURE, 9, false, DOC_PUBLIC_ID);

        assertThat(policy.keepsReplay(req, root)).isFalse();
    }

    @Test
    void questionCount_null_은_기본_6_으로_정규화해_비교한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        // 요청이 null 이면 6 으로 정규화되어 root(6)과 같다 → 유지.
        InterviewStartRequest req = request(null, "백엔드", Persona.PRESSURE, null, false, DOC_PUBLIC_ID);

        assertThat(policy.keepsReplay(req, root)).isTrue();
    }

    @Test
    void persona_가_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(null, "백엔드", Persona.FRIENDLY, 6, false, DOC_PUBLIC_ID);

        assertThat(policy.keepsReplay(req, root)).isFalse();
    }

    @Test
    void documentId_가_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(null, "백엔드", Persona.PRESSURE, 6, false, UUID.randomUUID());

        assertThat(policy.keepsReplay(req, root)).isFalse();
    }

    @Test
    void companyId_가_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, 10L);
        InterviewStartRequest req = request(20L, "백엔드", Persona.PRESSURE, 6, false, DOC_PUBLIC_ID);

        assertThat(policy.keepsReplay(req, root)).isFalse();
    }

    @Test
    void companyId_가_둘_다_null_이면_같다고_본다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(null, "백엔드", Persona.PRESSURE, 6, false, DOC_PUBLIC_ID);

        assertThat(policy.keepsReplay(req, root)).isTrue();
    }

    @Test
    void 회사를_새로_선택하면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(10L, "백엔드", Persona.PRESSURE, 6, false, DOC_PUBLIC_ID);

        assertThat(policy.keepsReplay(req, root)).isFalse();
    }

    @Test
    void jobRole_이_바뀌면_일반_면접() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        InterviewStartRequest req = request(null, "프론트엔드", Persona.PRESSURE, 6, false, DOC_PUBLIC_ID);

        assertThat(policy.keepsReplay(req, root)).isFalse();
    }

    @Test
    void hideQuestionText_만_바뀌면_재면접을_유지한다() {
        InterviewSession root = root(Persona.PRESSURE, 6, "백엔드", DOC_PUBLIC_ID, null);
        // root 는 hideQuestionText=false, 요청은 true 지만 비교 대상이 아니라 유지.
        InterviewStartRequest req = request(null, "백엔드", Persona.PRESSURE, 6, true, DOC_PUBLIC_ID);

        assertThat(policy.keepsReplay(req, root)).isTrue();
    }
}
