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

    /**
     * 질문 1개당 답변 제한 시간(초)으로 허용하는 값. 프론트 프리셋(60·90·120초)과 일치합니다.
     * {@code null} 은 "제한 없음"으로 여기와 별개로 허용하며, 그 외 값은 세션을 만들지 않고
     * {@code INVALID_REQUEST}(400)로 거부합니다(questionCount 화이트리스트와 같은 방식).
     */
    private static final Set<Integer> ALLOWED_ANSWER_TIME_LIMITS = Set.of(60, 90, 120);

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
        // 답변 제한 시간도 AI 호출·세션 저장 전에 검증합니다(허용: 60·90·120·null).
        Integer answerTimeLimitSec = resolveAnswerTimeLimitSec(request.answerTimeLimitSec());
        User user = findUser(userId);
        Document document = findUsableFileDocument(request.documentId(), userId);
        Company company = findVerifiedCompany(request.companyId());

        // 재면접 요청이면 대상 세션을 검증하고 root 기준으로 replay 구성을 결정합니다.
        // 옵션 비교에 쓸 값은 이미 조회·정규화한 document·company·questionCount 를 넘깁니다
        // (요청 원문 문자열을 다시 비교하지 않습니다). 일반 면접이면 replay == 적용 안 함(null).
        ReplayPlan replayPlan = resolveReplayPlan(userId, request, questionCount, document, company);

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
                // 정상 재면접이면 root 세션 ID 와 replay_log 를 함께 보냅니다. 일반 면접
                // (sourceSessionId 없음)이면 둘 다 null 입니다(하나만 보내면 안 됩니다).
                // 재면접 요청의 고정 옵션이 다르면 이 지점 전에 400 으로 거부됩니다.
                replayPlan.retryOfSessionId(),
                replayPlan.replayLog(),
                null);

        AiSessionStartResponse aiResponse = aiClient.startSession(aiRequest);

        // AI 세션은 이미 만들어졌습니다. 여기서 로컬 세션 저장이 실패하면 AI 쪽에
        // task 만 살아남아 정리할 주체가 없어집니다. 저장 실패 시 AI 세션을 보상
        // abort 하고, 정리 실패가 원래 예외를 덮지 않도록 suppressed 로 붙입니다.
        InterviewSession savedSession;
        try {
            savedSession = sessionWriter.createSession(
                    user, document, company, request, aiResponse, questionCount,
                    answerTimeLimitSec, replayPlan.rootSession());
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

        // 응답 옵션은 요청값이 아니라 저장된 세션의 확정값으로 구성합니다(Issue #99).
        return InterviewStartResponse.from(
                savedSession, aiResponse.questionTotal(), replayPlan.isApplied());
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
     *   <li>최초 root 세션이 <b>같은 사용자 소유로</b> 존재하는지({@code SESSION_NOT_FOUND})</li>
     *   <li>고정 옵션(문항 수·페르소나·이력서·기업·직무)이 root 와 모두 같은지 — 하나라도
     *       다르면 일반 면접으로 전환하지 않고 {@code INVALID_REQUEST}(400)로 <b>요청을 거부</b>
     *       합니다(팀 확정 정책). hideQuestionText·타이머는 비교하지 않습니다.</li>
     *   <li>root 질문 로그로 유효한 {@code replay_log} 를 조립할 수 있는지(비었거나 REASK
     *       만 있거나 필수 필드 누락이면 {@code INTERNAL_ERROR}) — 일반 면접으로 조용히
     *       전환하지 않고 오류를 냅니다</li>
     * </ol>
     *
     * <p>1~3단계(소유권·완료 상태·root fetch join 조회)는 재연습 옵션 조회 API 와 공유하는
     * {@link InterviewSessionQueryService#getReplayRoot} 가 처리합니다. 같은 단계를 두 곳에
     * 복사하지 않아, 한쪽만 바뀌어 조회·시작 판정이 어긋나는 일을 막습니다.
     *
     * <p>거부·오류 경로 모두 AI 호출과 새 세션 생성 이전에 끝나므로, 불일치 요청으로는
     * AI 세션이 만들어지지 않습니다.
     *
     * @param normalizedQuestionCount {@link #resolveQuestionCount} 로 정규화한 문항 수
     * @param document 요청이 조회해 둔 새 면접 문서(옵션 비교에 재사용)
     * @param company  요청이 조회해 둔 새 면접 기업. 연습 모드면 null
     */
    private ReplayPlan resolveReplayPlan(String userId, InterviewStartRequest request,
                                         int normalizedQuestionCount,
                                         Document document, Company company) {
        if (!request.isReplayRequested()) {
            return ReplayPlan.none();
        }

        // 1~3) 소유권·완료 상태 확인 후 최초 root 세션을 fetch join 으로 읽습니다.
        //    재연습 옵션 조회 API(ReplaySessionOptionsService)와 같은 메서드를 공유해,
        //    소유권·완료 상태·root 결정 단계를 양쪽에서 복사하지 않습니다(한쪽만 바뀌어
        //    조회는 되는데 시작은 실패하는 불일치 방지). 없거나 남의 세션은 SESSION_NOT_FOUND,
        //    완료 전이면 SESSION_NOT_COMPLETED, root 가 깨졌거나 남의 세션이면 SESSION_NOT_FOUND.
        //    옵션 비교에 쓰는 LAZY 연관(document·company)은 fetch join 으로 초기화돼 트랜잭션
        //    밖(open-in-view=false)에서 비교해도 LazyInitializationException 이 나지 않습니다.
        InterviewSession rootSession =
                sessionQueryService.getReplayRoot(request.sourceSessionId(), userId);
        String rootSessionId = rootSession.getSessionId();

        // 4) 고정 옵션 검증. 요청이 이미 조회·정규화한 값으로 비교합니다(요청 원문 문자열 아님).
        //    문항 수·페르소나·이력서·기업·직무가 최초 세션과 하나라도 다르면 일반 면접으로
        //    조용히 전환하지 않고 INVALID_REQUEST(400)로 요청을 거부합니다(팀 확정 정책).
        //    hideQuestionText·타이머는 질문 생성에 무관해 비교하지 않습니다.
        replayOptionPolicy.enforceFixedOptions(
                request, normalizedQuestionCount, document, company, rootSession);

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

    /**
     * 답변 제한 시간을 검증·정규화합니다. {@code null}(필드 누락 또는 명시적 null)은 "제한
     * 없음"으로 그대로 통과시키고, 값이 있으면 60·90·120 만 허용합니다. 그 외는 AI 호출·세션
     * 저장 전에 {@code INVALID_REQUEST}(400)로 거부합니다.
     *
     * <p>백엔드는 기본값(90초)을 적용하지 않습니다. 기본값은 프론트 UI 선택값으로 요청에
     * 실려 오며, 누락/명시 null 을 구별하지 않는 것은 {@code hideQuestionText} 관례와 같습니다.
     */
    private Integer resolveAnswerTimeLimitSec(Integer requested) {
        if (requested == null) {
            return null;
        }
        if (!ALLOWED_ANSWER_TIME_LIMITS.contains(requested)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "답변 제한 시간은 60, 90, 120 초 또는 제한 없음(null) 중 하나여야 합니다");
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
     * <p>일반 면접({@code sourceSessionId} 없음)이면 {@link #none()} 이며, 이때 AI 요청의
     * {@code retry_of_session_id}·{@code replay_log} 는 둘 다 null 이고 DB 에도 재면접
     * 연관({@code retryOfSession})을 저장하지 않습니다. 재면접 요청의 고정 옵션이 다르면
     * 일반 면접으로 전환하지 않고 그 전에 {@code INVALID_REQUEST}(400)로 거부되므로,
     * {@code none()} 은 일반 면접 요청에서만 나옵니다.
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

        /**
         * 재면접이 실제로 적용됐는지. 일반 면접({@code sourceSessionId} 없음)이면 false 입니다.
         * 응답의 {@code replayApplied} 로 내려갑니다. (고정 옵션 불일치는 그 전에 400 으로
         * 거부되므로 이 값이 false 인 채로 응답이 나가는 경우는 일반 면접뿐입니다.)
         */
        private boolean isApplied() {
            return rootSession != null;
        }
    }
}
