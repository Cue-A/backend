package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.entity.InterviewSession;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * 재면접 요청의 <b>고정 옵션</b>이 최초(root) 세션과 같은지 검증합니다.
 *
 * <h3>서비스 정책 (팀 확정)</h3>
 * 재면접은 최초 1회차와 <b>같은 주질문 세트</b>를 다시 출제하고 꼬리질문만 새로 생성해
 * 회차별 결과를 비교하는 기능입니다. 따라서 질문 생성에 영향을 주는 주요 옵션이 달라지면
 * 재면접의 의미가 없어지므로 <b>최초 세션 값으로 고정</b>합니다. 하나라도 다르면
 * 일반 면접으로 조용히 전환하지 않고 <b>요청을 거부</b>합니다.
 *
 * <table>
 *   <tr><th>옵션</th><th>다르면</th><th>근거</th></tr>
 *   <tr><td>questionCount</td><td>400 거부</td><td>고정</td></tr>
 *   <tr><td>persona</td><td>400 거부</td><td>고정</td></tr>
 *   <tr><td>documentId</td><td>400 거부</td><td>고정</td></tr>
 *   <tr><td>companyId</td><td>400 거부</td><td>고정</td></tr>
 *   <tr><td>jobRole</td><td>400 거부</td><td>고정</td></tr>
 *   <tr><td>hideQuestionText</td><td>허용</td><td>표시 방식만 바꿈. 질문 구성에 무관</td></tr>
 *   <tr><td>타이머</td><td>허용</td><td>프론트 표시 설정. Backend 요청/DB 에 없음</td></tr>
 * </table>
 *
 * <p>AI 계약 자체는 문항 수·페르소나 변경 시 일반 면접으로 처리하는 규칙이지만, 이번
 * 정책은 <b>Backend 가 불일치 요청을 사전에 거부</b>하는 서비스 정책입니다. AI 요청 JSON·DTO
 * 는 바꾸지 않습니다. docs/12-replay.md 참고.
 *
 * <p><b>비교 값은 {@link InterviewStartService} 가 이미 조회·정규화한 것을 넘겨받습니다.</b>
 * 요청 원문 문자열({@code documentId})을 직접 비교하지 않습니다. 문서는 조회된 {@link Document}
 * 의 {@code publicId}(UUID) 로 비교해, 대소문자만 다른 UUID 가 다른 문서로 오판되지 않게
 * 합니다. questionCount 기본값(6) 정규화도 서비스의 값을 받아 여기서 다시 정의하지 않습니다.
 */
@Component
public class ReplayOptionPolicy {

    /**
     * 재면접 고정 옵션이 root 세션과 모두 같은지 검증합니다. 하나라도 다르면
     * {@code INVALID_REQUEST}(400)로 요청을 거부합니다(일반 면접으로 전환하지 않습니다).
     *
     * @param request                 새 면접 시작 요청(persona·jobRole 비교용)
     * @param normalizedQuestionCount  {@link InterviewStartService} 가 정규화한 문항 수(null→6 적용 후)
     * @param document                요청이 조회해 둔 새 면접의 문서(필수)
     * @param company                 요청이 조회해 둔 새 면접의 기업. 연습 모드면 null
     * @param rootSession             회차 묶음의 최초 세션. document·company 는 fetch join 으로 초기화됨
     * @throws BusinessException 고정 옵션이 하나라도 다르면 {@code INVALID_REQUEST}
     */
    public void enforceFixedOptions(InterviewStartRequest request,
                                    int normalizedQuestionCount,
                                    Document document,
                                    Company company,
                                    InterviewSession rootSession) {
        if (normalizedQuestionCount != rootSession.getQuestionCount()) {
            throw mismatch("문항 수");
        }
        if (request.persona() != rootSession.getPersona()) {
            throw mismatch("면접관 스타일");
        }
        if (!sameDocument(document, rootSession)) {
            throw mismatch("이력서");
        }
        if (!sameCompany(company, rootSession)) {
            throw mismatch("기업");
        }
        if (!Objects.equals(request.jobRole(), rootSession.getJobRole())) {
            throw mismatch("직무");
        }
        // hideQuestionText·타이머는 비교하지 않습니다. 표시 방식만 바꾸므로 허용합니다.
    }

    private BusinessException mismatch(String optionLabel) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                "재연습은 최초 면접과 같은 조건으로만 시작할 수 있습니다. "
                        + optionLabel + "이(가) 최초 면접과 다릅니다.");
    }

    /**
     * 문서 동일성은 조회된 Document 의 {@code publicId}(UUID) 로 비교합니다. 요청 원문
     * 문자열을 쓰면 대소문자만 다른 UUID 가 다른 문서로 판정됩니다.
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
