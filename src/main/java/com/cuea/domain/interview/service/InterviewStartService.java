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
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiReplayLogItem;
import com.cuea.infrastructure.ai.dto.AiSessionStartRequest;
import com.cuea.infrastructure.ai.dto.AiSessionStartResponse;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
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

    /** AI 계약이 허용하는 문항 수. 그 외 값은 세션을 만들지 않고 거부합니다. */
    private static final Set<Integer> ALLOWED_QUESTION_COUNTS = Set.of(3, 6, 9);

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final CompanyRepository companyRepository;

    private final CompanyProfileFormatter companyProfileFormatter;
    private final PresignedUrlIssuer presignedUrlIssuer;
    private final AiClient aiClient;
    private final InterviewSessionWriter sessionWriter;
    private final InterviewFirstQuestionPoller firstQuestionPoller;
    private final InterviewSessionTerminator sessionTerminator;

    private final InterviewSessionQueryService sessionQueryService;
    private final ReplayLogAssembler replayLogAssembler;
    private final ReplayOptionPolicy replayOptionPolicy;

    /**
     * 세션을 시작하고 즉시 반환합니다. 첫 질문은 백그라운드 폴링 후 WebSocket 으로
     * 전달되므로, 프론트는 이 응답을 받는 즉시 {@code /ws/interviews/{sessionId}} 에
     * 연결해야 합니다.
     */
    public InterviewStartResponse start(String userId, InterviewStartRequest request) {
        int questionCount = resolveQuestionCount(request.questionCount());
        User user = findUser(userId);
        Document document = findUsableFileDocument(request.documentId(), userId);
        Company company = findVerifiedCompany(request.companyId());

        // 재면접 요청이면 대상 세션을 검증하고 root 기준으로 replay 구성을 결정합니다.
        // 일반 면접이면 replay == 적용 안 함(null) 입니다.
        ReplayPlan replayPlan = resolveReplayPlan(userId, request);

        String resumeFileUrl = presignedUrlIssuer.issueResumeDownload(document.getObjectKey());
        String companyProfileOverride = company == null ? null : companyProfileFormatter.format(company);

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
                // 재면접이면 root 세션 ID 와 replay_log 를 함께 보냅니다. 옵션 변경으로
                // 일반 면접으로 전환됐으면 둘 다 null 입니다(하나만 보내면 안 됩니다).
                replayPlan.retryOfSessionId(),
                replayPlan.replayLog(),
                null);

        AiSessionStartResponse aiResponse = aiClient.startSession(aiRequest);

        // AI 세션은 이미 만들어졌습니다. 여기서 로컬 세션 저장이 실패하면 AI 쪽에
        // task 만 살아남아 정리할 주체가 없어집니다. 저장 실패 시 AI 세션을 보상
        // abort 하고, 정리 실패가 원래 예외를 덮지 않도록 suppressed 로 붙입니다.
        try {
            sessionWriter.createSession(
                    user, document, company, request, aiResponse, questionCount,
                    replayPlan.rootSession());
        } catch (RuntimeException e) {
            log.warn("로컬 세션 저장 실패로 AI 세션을 중단합니다 sessionId={}", aiResponse.sessionId());
            // 세션 저장이 실패했으니 우리 DB 에는 세션 행이 없습니다. AI 세션만 정리합니다.
            abortAiSessionQuietly(aiResponse.sessionId(), e);
            throw e;
        }

        // 폴링(최대 90초)은 HTTP 요청 스레드에서 기다리지 않고 백그라운드로 넘깁니다.
        // @Async 태스크 제출 자체가 실패하면(예: 종료 중 TaskRejectedException) 세션이
        // 이미 IN_PROGRESS 로 저장돼 있으므로, 폴링이 시작조차 못 해 영구 잔류합니다.
        // 그 경우 세션을 ABORTED 로 정리하고 AI 세션도 중단합니다.
        try {
            firstQuestionPoller.pollAndDeliver(
                    aiResponse.sessionId(), aiResponse.taskId(), aiResponse.questionTotal());
        } catch (RuntimeException e) {
            log.warn("첫 질문 폴링 시작에 실패해 세션을 정리합니다 sessionId={}", aiResponse.sessionId(), e);
            sessionTerminator.terminateQuietly(aiResponse.sessionId(), e);
            throw e;
        }

        return new InterviewStartResponse(aiResponse.sessionId(), aiResponse.questionTotal());
    }

    /**
     * 로컬 세션 저장이 실패한 경우 전용 보상입니다. 이때는 우리 DB 에 세션 행이 아직
     * 없으므로 {@link InterviewSessionTerminator}(로컬 {@code ABORTED} 정리 포함)를 쓰지
     * 않고 AI 세션만 abort 합니다. 실패해도 원인 예외({@code cause})를 덮지 않습니다.
     */
    private void abortAiSessionQuietly(String sessionId, RuntimeException cause) {
        try {
            aiClient.abortSession(sessionId);
        } catch (RuntimeException cleanupError) {
            cause.addSuppressed(cleanupError);
            log.warn("AI 세션 중단 실패 sessionId={}", sessionId, cleanupError);
        }
    }

    /**
     * 재면접 요청을 검증·해석해 실행 계획을 만듭니다.
     *
     * <p>일반 면접({@code sourceSessionId} 없음)이면 {@link ReplayPlan#none()} 을 돌려줍니다.
     * 재면접이면 다음을 검증합니다(모두 통과해야 replay 를 구성합니다).
     * <ol>
     *   <li>대상 세션이 존재하고 요청 사용자 소유인지({@code SESSION_NOT_FOUND})</li>
     *   <li>대상 세션이 완료 상태인지({@code SESSION_NOT_COMPLETED})</li>
     *   <li>최초 root 세션이 존재하는지({@code SESSION_NOT_FOUND})</li>
     *   <li>옵션(문항 수·페르소나·문서·기업·직무)이 root 와 같은지 — 다르면 일반 면접으로
     *       전환해 {@code retry_of_session_id}·{@code replay_log} 를 둘 다 생략</li>
     *   <li>root 질문 로그로 유효한 {@code replay_log} 를 조립할 수 있는지(비었거나 REASK
     *       만 있거나 필수 필드 누락이면 {@code INVALID_REQUEST}) — 일반 면접으로 조용히
     *       전환하지 않고 오류를 냅니다</li>
     * </ol>
     */
    private ReplayPlan resolveReplayPlan(String userId, InterviewStartRequest request) {
        if (!request.isReplayRequested()) {
            return ReplayPlan.none();
        }

        // 1) 소유권 + 존재. 없거나 남의 세션이면 구별 없이 404.
        InterviewSession source =
                sessionQueryService.getOwnedSession(request.sourceSessionId(), userId);

        // 2) 완료 상태만 재면접 대상입니다. 진행 중·중단 세션은 거부합니다.
        if (!source.getStatus().isReportable()) {
            throw new BusinessException(ErrorCode.SESSION_NOT_COMPLETED,
                    "완료된 세션만 다시 면접할 수 있습니다");
        }

        // 3) root 세션 결정. 직전 회차가 아니라 항상 최초 세션입니다(docs/12-replay.md).
        //    옵션 비교에 쓰는 LAZY 연관(document·company)을 fetch join 으로 함께 읽어,
        //    트랜잭션 밖(open-in-view=false)에서 비교해도 LazyInitializationException 이
        //    나지 않게 합니다. source 자신이 root 여도 연관 초기화를 위해 다시 읽습니다.
        String rootSessionId = source.rootSessionId();
        InterviewSession rootSession = sessionQueryService.getRootForReplay(rootSessionId);

        // 4) 옵션 변경 판정. 하나라도 비교 대상 옵션이 다르면 일반 면접으로 전환해
        //    retry_of_session_id·replay_log 를 둘 다 생략합니다.
        if (!replayOptionPolicy.keepsReplay(request, rootSession)) {
            log.info("옵션 변경으로 재면접을 일반 면접으로 전환합니다 sourceSessionId={} rootSessionId={}",
                    request.sourceSessionId(), rootSessionId);
            return ReplayPlan.none();
        }

        // 5) replay_log 조립. 비었거나 REASK 만 있거나 필수 필드 누락이면 여기서 예외.
        List<AiReplayLogItem> replayLog = replayLogAssembler.assemble(rootSessionId);

        return ReplayPlan.of(rootSession, replayLog);
    }

    /**
     * null 이면 계약 기본값 6, 값이 있으면 3·6·9 만 허용합니다. 그 외는 AI 호출 전에
     * {@code INVALID_QUESTION_COUNT} 로 거부합니다.
     */
    private int resolveQuestionCount(Integer requested) {
        if (requested == null) {
            return DEFAULT_QUESTION_COUNT;
        }
        if (!ALLOWED_QUESTION_COUNTS.contains(requested)) {
            throw new BusinessException(ErrorCode.INVALID_QUESTION_COUNT);
        }
        return requested;
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * 세션에 붙일 수 있는 문서를 찾습니다.
     *
     * <p>AI 세션 시작에는 presigned URL 을 만들 수 있는 실제 파일이 필요합니다. 파일
     * 문서도 마크다운 문서도 S3 에 파일을 갖고 있으므로(Issue #36) {@code sourceType}
     * 이 아니라 {@code objectKey} 유무로 판단합니다. 없는 것은 Issue #36 이전에 등록된
     * 마크다운 문서뿐이라, 다시 등록하라고 안내합니다.
     */
    private Document findUsableFileDocument(String documentPublicId, String userId) {
        Document document = documentRepository.findByPublicIdAndUser_UserId(
                        parsePublicId(documentPublicId), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

        if (!document.isUsableForSession()) {
            throw new BusinessException(ErrorCode.UPLOAD_NOT_COMPLETED,
                    "문서가 아직 준비되지 않았습니다");
        }
        if (!document.hasStoredFile()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_FORMAT,
                    "이 문서는 면접에 쓸 수 없습니다. 같은 내용으로 다시 등록해 주세요");
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

    /**
     * 재면접 실행 계획. 재면접이 실제로 적용될 때만 {@code rootSession} 과 {@code replayLog}
     * 가 채워집니다.
     *
     * <p>일반 면접이거나 옵션 변경으로 전환된 경우 {@link #none()} 이며, 이때 AI 요청의
     * {@code retry_of_session_id}·{@code replay_log} 는 둘 다 null 이고 DB 에도 재면접
     * 연관({@code retryOfSession})을 저장하지 않습니다.
     *
     * @param rootSession replay 적용 시 연관으로 저장할 최초 세션. 미적용이면 null
     * @param replayLog   AI 에 보낼 replay_log. 미적용이면 null
     */
    private record ReplayPlan(InterviewSession rootSession, List<AiReplayLogItem> replayLog) {

        private static ReplayPlan none() {
            return new ReplayPlan(null, null);
        }

        private static ReplayPlan of(InterviewSession rootSession, List<AiReplayLogItem> replayLog) {
            return new ReplayPlan(rootSession, replayLog);
        }

        /** AI 요청의 retry_of_session_id. replay 미적용이면 null 입니다. */
        private String retryOfSessionId() {
            return rootSession == null ? null : rootSession.getSessionId();
        }
    }
}
