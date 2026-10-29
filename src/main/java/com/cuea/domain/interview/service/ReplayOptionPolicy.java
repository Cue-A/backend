package com.cuea.domain.interview.service;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.entity.InterviewSession;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * 재면접 요청의 옵션이 최초(root) 세션과 같은지 비교해, 재면접을 유지할지 일반 면접으로
 * 전환할지 판정합니다.
 *
 * <h3>판정 규칙</h3>
 * <table>
 *   <tr><th>옵션</th><th>변경 시</th><th>근거</th></tr>
 *   <tr><td>questionCount</td><td>일반 면접</td><td><b>AI 계약 확정</b></td></tr>
 *   <tr><td>persona</td><td>일반 면접</td><td><b>AI 계약 확정</b></td></tr>
 *   <tr><td>documentId</td><td>일반 면접</td><td>Backend 제안 정책(AI 미확정, 팀 합의 대기)</td></tr>
 *   <tr><td>companyId</td><td>일반 면접</td><td>Backend 제안 정책(AI 미확정, 팀 합의 대기)</td></tr>
 *   <tr><td>jobRole</td><td>일반 면접</td><td>Backend 제안 정책(AI 미확정, 팀 합의 대기)</td></tr>
 *   <tr><td>hideQuestionText</td><td>재면접 유지</td><td>표시 방식만 바꿈. 질문 구성에 무관</td></tr>
 * </table>
 *
 * <p>AI 계약으로 확정된 축은 questionCount·persona 뿐입니다. document·company·jobRole 은
 * Backend 가 제안한 정책이며 팀 합의가 끝나지 않았습니다. docs/90-open-questions.md 참고.
 *
 * <p><b>비교에 쓰는 값은 {@link InterviewStartService} 가 이미 조회·정규화한 것을 넘겨받습니다.</b>
 * 요청 원문 문자열({@code documentId})을 직접 비교하지 않습니다. 문서는 조회된 {@link Document}
 * 의 {@code publicId}(UUID) 로 비교해, 대소문자만 다른 UUID 가 일반 면접으로 잘못 전환되지
 * 않게 합니다({@code UUID.fromString} 은 대소문자를 무시하지만 문자열 비교는 구분하기 때문).
 * questionCount 기본값(6)도 서비스의 정규화된 값을 받아 여기서 다시 정의하지 않습니다.
 */
@Component
public class ReplayOptionPolicy {

    /**
     * 재면접을 유지할 수 있는 옵션인지. 하나라도 비교 대상 옵션이 다르면 false 를
     * 돌려주고, 호출부는 일반 면접으로 전환합니다.
     *
     * @param request              새 면접 시작 요청(persona·jobRole 비교용)
     * @param normalizedQuestionCount {@link InterviewStartService} 가 정규화한 문항 수(null→6 적용 후)
     * @param document             요청이 조회해 둔 새 면접의 문서(필수, 세션 시작에 항상 존재)
     * @param company              요청이 조회해 둔 새 면접의 기업. 연습 모드면 null
     * @param rootSession          회차 묶음의 최초 세션. document·company 는 fetch join 으로 초기화돼 있음
     * @return 모든 비교 대상 옵션이 같으면 true(재면접 유지), 하나라도 다르면 false(일반 전환)
     */
    public boolean keepsReplay(InterviewStartRequest request,
                               int normalizedQuestionCount,
                               Document document,
                               Company company,
                               InterviewSession rootSession) {
        return normalizedQuestionCount == rootSession.getQuestionCount()
                && request.persona() == rootSession.getPersona()
                && sameDocument(document, rootSession)
                && sameCompany(company, rootSession)
                && Objects.equals(request.jobRole(), rootSession.getJobRole());
        // hideQuestionText 는 비교하지 않습니다. 표시 방식만 바꾸므로 재면접을 유지합니다.
    }

    /**
     * 문서 동일성은 조회된 Document 의 {@code publicId}(UUID) 로 비교합니다. 요청 원문
     * 문자열을 쓰면 대소문자만 다른 UUID 가 다른 문서로 판정돼 재면접이 조용히 깨집니다.
     */
    private boolean sameDocument(Document document, InterviewSession rootSession) {
        UUID requestedDocId = document.getPublicId();
        UUID rootDocId = rootSession.getDocument().getPublicId();
        return Objects.equals(requestedDocId, rootDocId);
    }

    private boolean sameCompany(Company company, InterviewSession rootSession) {
        Long requestedCompanyId = company == null ? null : company.getCompanyId();
        Long rootCompanyId = rootSession.getCompany() == null
                ? null
                : rootSession.getCompany().getCompanyId();
        return Objects.equals(requestedCompanyId, rootCompanyId);
    }
}
