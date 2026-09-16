package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.repository.CompanyRepository;
import com.cuea.domain.company.service.CompanyProfileFormatter;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.repository.DocumentRepository;
import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.dto.response.InterviewStartResponse;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiSessionStartRequest;
import com.cuea.infrastructure.ai.dto.AiSessionStartResponse;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 면접 시작 흐름의 진입점.
 *
 * <p>두 단계로 나뉩니다.
 * <ol>
 *   <li><b>{@link #start} (동기, HTTP 요청 스레드)</b> — Document/Company 조회 →
 *       {@code POST /ai/sessions} 호출 → session_id/task_id 수신 → 세션 저장.
 *       여기까지만 하고 REST 응답을 <b>빠르게</b> 반환합니다.</li>
 *   <li><b>{@link InterviewFirstQuestionPoller#pollAndDeliver} (백그라운드)</b> —
 *       task_id 를 최대 90초 폴링 → 첫 질문 저장 → WebSocket push. 결과·오류 모두
 *       WebSocket 으로만 전달됩니다.</li>
 * </ol>
 *
 * <p>AI 계약은 {@code task_id} 를 즉시 반환하는 비동기 모델입니다. 폴링을 HTTP
 * 요청 스레드에서 기다리면 응답이 최대 90초 늦어지므로, 폴링은
 * {@link InterviewFirstQuestionPoller}(가상 스레드 {@code @Async})로 넘깁니다.
 *
 * <p><b>{@code @Transactional} 을 이 클래스에 붙이지 않습니다.</b> DB 읽기·쓰기는
 * 짧은 트랜잭션을 가진 {@link InterviewSessionWriter} 로 위임하고, 그 사이에 AI
 * 호출을 배치합니다. docs/01-conventions.md 의 트랜잭션 항목 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewStartService {

    /** AI 계약: question_count 를 안 보내면 AI 기본값 6이 적용됩니다. */
    private static final int DEFAULT_QUESTION_COUNT = 6;

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final CompanyRepository companyRepository;

    private final CompanyProfileFormatter companyProfileFormatter;
    private final PresignedUrlIssuer presignedUrlIssuer;
    private final AiClient aiClient;
    private final InterviewSessionWriter sessionWriter;
    private final InterviewFirstQuestionPoller firstQuestionPoller;

    /**
     * 세션을 시작하고 즉시 반환합니다. 첫 질문은 백그라운드 폴링 후 WebSocket 으로
     * 전달되므로, 프론트는 이 응답을 받는 즉시 {@code /ws/interviews/{sessionId}} 에
     * 연결해야 합니다.
     */
    public InterviewStartResponse start(String userId, InterviewStartRequest request) {
        User user = findUser(userId);
        Document document = findUsableDocument(request.documentPublicId(), userId);
        Company company = findVerifiedCompany(request.companyId());

        String resumeFileUrl = presignedUrlIssuer.issueResumeDownload(document.getObjectKey());
        String companyProfileOverride = company == null ? null : companyProfileFormatter.format(company);
        int questionCount = request.questionCount() != null ? request.questionCount() : DEFAULT_QUESTION_COUNT;

        AiSessionStartRequest aiRequest = new AiSessionStartRequest(
                resumeFileUrl,
                request.jobRole(),
                request.persona().toAiValue(),
                // AI 의 company_id 는 더 이상 기업 조회 key 가 아닙니다. AI 는 이 값을
                // 로그·문제 추적용 opaque ID 로만 씁니다. Backend Company 의 BIGINT PK 를
                // 문자열로 변환해 넣습니다(회사 미선택이면 null). 질문 생성에 필요한 기업
                // 정보는 company_profile_override 로 전달합니다.
                company == null ? null : String.valueOf(company.getCompanyId()),
                companyProfileOverride,
                questionCount,
                null,
                null,
                null);

        AiSessionStartResponse aiResponse = aiClient.startSession(aiRequest);

        sessionWriter.createSession(user, document, company, request, aiResponse, questionCount);

        // 폴링(최대 90초)은 HTTP 요청 스레드에서 기다리지 않고 백그라운드로 넘깁니다.
        firstQuestionPoller.pollAndDeliver(
                aiResponse.sessionId(), aiResponse.taskId(), aiResponse.questionTotal());

        return new InterviewStartResponse(aiResponse.sessionId(), aiResponse.questionTotal());
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private Document findUsableDocument(String documentPublicId, String userId) {
        Document document = documentRepository.findByPublicIdAndUser_UserId(
                        parsePublicId(documentPublicId), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

        if (!document.isUsableForSession()) {
            throw new BusinessException(ErrorCode.UPLOAD_NOT_COMPLETED,
                    "문서가 아직 준비되지 않았습니다");
        }
        return document;
    }

    private UUID parsePublicId(String documentPublicId) {
        try {
            return UUID.fromString(documentPublicId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
    }

    private Company findVerifiedCompany(Long companyId) {
        if (companyId == null) {
            return null;
        }
        return companyRepository.findByCompanyIdAndVerifiedTrue(companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "선택한 기업을 찾을 수 없습니다"));
    }
}
