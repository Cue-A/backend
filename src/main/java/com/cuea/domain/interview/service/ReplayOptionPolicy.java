package com.cuea.domain.interview.service;

import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.entity.InterviewSession;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 재면접 요청의 옵션이 최초(root) 세션과 같은지 비교해, 재면접을 유지할지 일반 면접으로
 * 전환할지 판정합니다.
 *
 * <h3>판정 규칙</h3>
 * <table>
 *   <tr><th>옵션</th><th>변경 시</th><th>근거</th></tr>
 *   <tr><td>questionCount</td><td>일반 면접</td><td>AI 계약 확정</td></tr>
 *   <tr><td>persona</td><td>일반 면접</td><td>AI 계약 확정</td></tr>
 *   <tr><td>documentId</td><td>일반 면접</td><td>Backend 정책</td></tr>
 *   <tr><td>companyId</td><td>일반 면접</td><td>Backend 정책</td></tr>
 *   <tr><td>jobRole</td><td>일반 면접</td><td>Backend 정책</td></tr>
 *   <tr><td>hideQuestionText</td><td>재면접 유지</td><td>표시 방식만 바꿈. 질문 구성에 무관</td></tr>
 * </table>
 *
 * <p>questionCount 는 일반 면접 로직과 같은 방식으로 정규화(null → 기본 6)한 뒤 비교합니다.
 * root 세션의 questionCount 는 저장 시 이미 정규화된 {@code int} 입니다. docs/12-replay.md 참고.
 */
@Component
public class ReplayOptionPolicy {

    /** 일반 면접 로직과 동일한 기본 문항 수. */
    private static final int DEFAULT_QUESTION_COUNT = 6;

    /**
     * 재면접을 유지할 수 있는 옵션인지. 하나라도 비교 대상 옵션이 다르면 false 를
     * 돌려주고, 호출부는 일반 면접으로 전환합니다.
     *
     * @param request 새 면접 시작 요청
     * @param rootSession 회차 묶음의 최초 세션
     * @return 모든 비교 대상 옵션이 같으면 true(재면접 유지), 하나라도 다르면 false(일반 전환)
     */
    public boolean keepsReplay(InterviewStartRequest request, InterviewSession rootSession) {
        return sameQuestionCount(request, rootSession)
                && request.persona() == rootSession.getPersona()
                && sameDocument(request, rootSession)
                && sameCompany(request, rootSession)
                && Objects.equals(request.jobRole(), rootSession.getJobRole());
        // hideQuestionText 는 비교하지 않습니다. 표시 방식만 바꾸므로 재면접을 유지합니다.
    }

    private boolean sameQuestionCount(InterviewStartRequest request, InterviewSession rootSession) {
        int requested = request.questionCount() == null
                ? DEFAULT_QUESTION_COUNT
                : request.questionCount();
        return requested == rootSession.getQuestionCount();
    }

    private boolean sameDocument(InterviewStartRequest request, InterviewSession rootSession) {
        // request.documentId 는 문서 public_id(UUID) 의 문자열, root 세션은 Document 연관입니다.
        String rootDocumentId = rootSession.getDocument().getPublicId().toString();
        return Objects.equals(request.documentId(), rootDocumentId);
    }

    private boolean sameCompany(InterviewStartRequest request, InterviewSession rootSession) {
        Long rootCompanyId = rootSession.getCompany() == null
                ? null
                : rootSession.getCompany().getCompanyId();
        return Objects.equals(request.companyId(), rootCompanyId);
    }
}
