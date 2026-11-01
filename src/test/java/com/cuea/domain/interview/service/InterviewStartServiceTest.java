package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.domain.company.repository.CompanyRepository;
import com.cuea.domain.company.service.CompanyProfileFormatter;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.document.entity.SourceType;
import com.cuea.domain.document.repository.DocumentRepository;
import com.cuea.domain.interview.dto.request.InterviewStartRequest;
import com.cuea.domain.interview.dto.response.InterviewStartResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiReplayLogItem;
import com.cuea.infrastructure.ai.dto.AiSessionStartRequest;
import com.cuea.infrastructure.ai.dto.AiSessionStartResponse;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 세션 시작 REST 동기 구간을 검증합니다.
 *
 * <p>여기서 검증하는 것: Document/Company 조회 → AI 세션 생성 → 세션 저장 →
 * 백그라운드 폴러 위임 → 즉시 반환. <b>폴링·첫 질문 저장·WebSocket push 는 이
 * 서비스가 하지 않고</b> {@link InterviewFirstQuestionPoller} 에 위임하므로, 그
 * 위임 호출만 확인하고 폴링 자체는 {@code InterviewFirstQuestionPollerTest} 에서
 * 검증합니다.
 */
class InterviewStartServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID DOCUMENT_PUBLIC_ID = UUID.randomUUID();

    private DocumentRepository documentRepository;
    private CompanyRepository companyRepository;
    private AiClient aiClient;
    private InterviewSessionWriter sessionWriter;
    private InterviewFirstQuestionPoller firstQuestionPoller;
    private PresignedUrlIssuer presignedUrlIssuer;
    private InterviewSessionQueryService sessionQueryService;
    private ReplayLogAssembler replayLogAssembler;
    private ReplayOptionPolicy replayOptionPolicy;
    private InterviewStartService service;

    private Document document;
    private User user;

    @BeforeEach
    void setUp() {
        UserRepository userRepository = mock(UserRepository.class);
        documentRepository = mock(DocumentRepository.class);
        companyRepository = mock(CompanyRepository.class);
        aiClient = mock(AiClient.class);
        sessionWriter = mock(InterviewSessionWriter.class);
        firstQuestionPoller = mock(InterviewFirstQuestionPoller.class);

        presignedUrlIssuer = mock(PresignedUrlIssuer.class);
        when(presignedUrlIssuer.issueResumeDownload(anyString()))
                .thenReturn("https://s3.example.com/resume.pdf?presigned=1");

        sessionQueryService = mock(InterviewSessionQueryService.class);
        replayLogAssembler = mock(ReplayLogAssembler.class);
        // enforceFixedOptions 는 void 라 기본 mock 이 아무것도 하지 않음(= 옵션 일치로 통과).
        // 불일치 거부 케이스는 각 테스트에서 doThrow 로 stub 한다.
        replayOptionPolicy = mock(ReplayOptionPolicy.class);

        user = User.create("kim@example.com", "김취준");
        document = document(DocumentStatus.READY);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(documentRepository.findByPublicIdAndUser_UserId(DOCUMENT_PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(document));

        service = new InterviewStartService(
                userRepository, documentRepository, companyRepository,
                new CompanyProfileFormatter(), presignedUrlIssuer,
                aiClient, sessionWriter, firstQuestionPoller,
                new InterviewSessionTerminator(aiClient, sessionWriter),
                sessionQueryService, replayLogAssembler, replayOptionPolicy);
    }

    private Document document(DocumentStatus status) {
        return Document.builder()
                .docId(1L)
                .publicId(DOCUMENT_PUBLIC_ID)
                .user(user)
                .docTitle("포트폴리오")
                .docType(DocType.PORTFOLIO)
                .sourceType(SourceType.FILE)
                .objectKey("resumes/user-1/1.pdf")
                .status(status)
                .build();
    }

    private Document markdown(String objectKey) {
        return Document.builder()
                .docId(2L)
                .publicId(DOCUMENT_PUBLIC_ID)
                .user(user)
                .docTitle("자기소개서")
                .docType(DocType.RESUME)
                .sourceType(SourceType.MARKDOWN)
                .docText("직접 입력한 본문")
                .objectKey(objectKey)
                .status(DocumentStatus.READY)
                .build();
    }

    private InterviewStartRequest request(Long companyId) {
        return new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), companyId, "백엔드 개발", Persona.PRESSURE, 6, null, null);
    }

    private InterviewSession fakeSession(String sessionId) {
        return InterviewSession.builder()
                .sessionId(sessionId)
                .user(user)
                .document(document)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(6)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(SessionStatus.IN_PROGRESS)
                .build();
    }

    private Company verifiedCompany() {
        return Company.builder()
                .companyId(10L)
                .companyName("현대건설(주)")
                .industry("종합건설 · 플랜트")
                .valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전", "indicator", "두려워하지 않는다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(java.time.LocalDate.now())
                .verified(true)
                .build();
    }

    @Test
    void 세션을_생성하면_REST_는_첫질문을_기다리지_않고_즉시_반환하고_폴링은_백그라운드로_위임한다() {
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_9f2a1c", "task_001", 9);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(eq(user), eq(document), eq(null), any(), eq(aiResponse), eq(6), eq(null)))
                .thenReturn(fakeSession("sess_9f2a1c"));

        InterviewStartResponse response = service.start(USER_ID, request(null));

        assertThat(response.sessionId()).isEqualTo("sess_9f2a1c");
        assertThat(response.questionTotal()).isEqualTo(9);
        // 일반 면접이므로 replayApplied=false.
        assertThat(response.replayApplied()).isFalse();
        // REST 동기 구간에서는 세션 생성까지만 하고, 폴링/첫질문은 백그라운드에 위임한다.
        verify(sessionWriter).createSession(eq(user), eq(document), eq(null), any(), eq(aiResponse), eq(6), eq(null));
        verify(firstQuestionPoller).pollAndDeliver("sess_9f2a1c", "task_001", 9);
        // 이 서비스는 첫 질문을 직접 저장하지 않는다.
        verify(sessionWriter, never()).saveFirstQuestion(anyString(), any());
    }

    @Test
    void 기업을_선택하면_company_profile_override_와_company_id_문자열을_함께_보낸다() {
        when(companyRepository.findByCompanyIdAndVerifiedTrue(10L)).thenReturn(Optional.of(verifiedCompany()));
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_2", "task_2", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_2"));

        service.start(USER_ID, request(10L));

        ArgumentCaptor<AiSessionStartRequest> captor = ArgumentCaptor.forClass(AiSessionStartRequest.class);
        verify(aiClient).startSession(captor.capture());
        AiSessionStartRequest sent = captor.getValue();
        assertThat(sent.companyProfileOverride())
                .isEqualTo("현대건설(주) (종합건설 · 플랜트)\n핵심 가치\n  도전 — 두려워하지 않는다.");
        // company_id 는 Backend company PK(Long 10)의 문자열 표현. AI 조회 key 가 아니라
        // 로그·추적용 opaque ID 다.
        assertThat(sent.companyId()).isEqualTo("10");
        assertThat(sent.persona()).isEqualTo("pressure");
    }

    @Test
    void 기업을_선택하면_직렬화_JSON_에_company_id_문자열이_포함된다() throws Exception {
        when(companyRepository.findByCompanyIdAndVerifiedTrue(10L)).thenReturn(Optional.of(verifiedCompany()));
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_2", "task_2", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_2"));

        service.start(USER_ID, request(10L));

        ArgumentCaptor<AiSessionStartRequest> captor = ArgumentCaptor.forClass(AiSessionStartRequest.class);
        verify(aiClient).startSession(captor.capture());

        // @AiJson(snake_case) 직렬화 결과에 "company_id":"17" 형태가 들어가는지 확인.
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(captor.getValue());
        assertThat(json).contains("\"company_id\":\"10\"");
    }

    @Test
    void 회사_미선택이면_company_id_는_null_이라_JSON_에서_빠진다() throws Exception {
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_3", "task_3", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), eq(null), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_3"));

        service.start(USER_ID, request(null));

        ArgumentCaptor<AiSessionStartRequest> captor = ArgumentCaptor.forClass(AiSessionStartRequest.class);
        verify(aiClient).startSession(captor.capture());
        assertThat(captor.getValue().companyId()).isNull();
        assertThat(captor.getValue().companyProfileOverride()).isNull();

        // NON_NULL 정책이라 회사 미선택이면 company_id 키 자체가 JSON 에서 빠진다.
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(captor.getValue());
        assertThat(json).doesNotContain("company_id");
    }

    @Test
    void verified_false_기업으로는_면접을_시작할_수_없다() {
        // verified=false 기업은 findByCompanyIdAndVerifiedTrue 가 빈 값을 돌려준다.
        when(companyRepository.findByCompanyIdAndVerifiedTrue(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(USER_ID, request(99L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(aiClient, never()).startSession(any());
        verify(firstQuestionPoller, never()).pollAndDeliver(anyString(), anyString(), any());
    }

    @Test
    void 문서가_준비되지_않았으면_세션을_시작하지_않는다() {
        when(documentRepository.findByPublicIdAndUser_UserId(DOCUMENT_PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(document(DocumentStatus.PARSING)));

        assertThatThrownBy(() -> service.start(USER_ID, request(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_NOT_COMPLETED);

        verify(aiClient, never()).startSession(any());
        verify(firstQuestionPoller, never()).pollAndDeliver(anyString(), anyString(), any());
    }

    @Test
    void 존재하지_않는_문서면_DOCUMENT_NOT_FOUND_를_던진다() {
        when(documentRepository.findByPublicIdAndUser_UserId(DOCUMENT_PUBLIC_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(USER_ID, request(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    void 음성만_모드_요청은_hideQuestionText_true_로_세션_생성에_전달되고_AI_요청에는_포함되지_않는다() throws Exception {
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_v", "task_v", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_v"));

        service.start(USER_ID, new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), null, "백엔드 개발", Persona.FRIENDLY, 6, true, null));

        ArgumentCaptor<InterviewStartRequest> reqCaptor = ArgumentCaptor.forClass(InterviewStartRequest.class);
        verify(sessionWriter).createSession(any(), any(), any(), reqCaptor.capture(), any(), eq(6), any());
        assertThat(reqCaptor.getValue().hideQuestionText()).isTrue();

        // 음성만 모드는 UI 표시 방식만 바꾼다. AI 세션 시작 요청에는 진행 방식 필드가 없다.
        ArgumentCaptor<AiSessionStartRequest> aiCaptor = ArgumentCaptor.forClass(AiSessionStartRequest.class);
        verify(aiClient).startSession(aiCaptor.capture());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(aiCaptor.getValue());
        assertThat(json).doesNotContain("hide_question_text");
    }

    @Test
    void questionCount_가_null_이면_기본값_6_으로_AI_에_보낸다() {
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_q", "task_q", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_q"));

        service.start(USER_ID, new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), null, "백엔드 개발", Persona.FRIENDLY, null, null, null));

        ArgumentCaptor<AiSessionStartRequest> captor = ArgumentCaptor.forClass(AiSessionStartRequest.class);
        verify(aiClient).startSession(captor.capture());
        assertThat(captor.getValue().questionCount()).isEqualTo(6);
    }

    @Test
    void questionCount_가_3_6_9_외의_값이면_AI_호출_전에_거부한다() {
        assertThatThrownBy(() -> service.start(USER_ID, new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), null, "백엔드 개발", Persona.FRIENDLY, 4, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUESTION_COUNT);

        // 0 도 마찬가지로 거부(mock 이 session_end 를 첫 결과로 주는 값).
        assertThatThrownBy(() -> service.start(USER_ID, new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), null, "백엔드 개발", Persona.FRIENDLY, 0, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUESTION_COUNT);

        verify(aiClient, never()).startSession(any());
        verify(firstQuestionPoller, never()).pollAndDeliver(anyString(), anyString(), any());
    }

    /** Issue #36: 마크다운도 본문 사본을 S3 에 두므로 파일 문서와 똑같이 면접을 시작합니다. */
    @Test
    void S3_사본이_있는_MARKDOWN_문서로_면접을_시작한다() {
        Document markdown = markdown("resumes/user-1/2.txt");
        when(documentRepository.findByPublicIdAndUser_UserId(DOCUMENT_PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(markdown));
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_md", "task_md", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_md"));

        service.start(USER_ID, request(null));

        verify(presignedUrlIssuer).issueResumeDownload("resumes/user-1/2.txt");
        verify(aiClient).startSession(any());
    }

    /** Issue #36 이전에 등록된 마크다운 문서는 S3 사본이 없어 AI 에 넘길 URL 을 만들 수 없습니다. */
    @Test
    void S3_사본이_없는_예전_MARKDOWN_문서는_AI_호출_전에_거부한다() {
        when(documentRepository.findByPublicIdAndUser_UserId(DOCUMENT_PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(markdown(null)));

        assertThatThrownBy(() -> service.start(USER_ID, request(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_FILE_FORMAT);

        verify(aiClient, never()).startSession(any());
        verify(firstQuestionPoller, never()).pollAndDeliver(anyString(), anyString(), any());
    }

    @Test
    void 로컬_세션_저장이_실패하면_AI_세션을_abort_하고_원본_예외를_던진다() {
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_x", "task_x", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        BusinessException writeError = new BusinessException(ErrorCode.INTERNAL_ERROR, "DB 저장 실패");
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any())).thenThrow(writeError);

        assertThatThrownBy(() -> service.start(USER_ID, request(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);

        // AI 세션은 이미 만들어졌으므로 보상 abort 를 시도해야 한다.
        verify(aiClient).abortSession("sess_x");
        // 저장이 실패했으니 폴러는 시작하지 않는다.
        verify(firstQuestionPoller, never()).pollAndDeliver(anyString(), anyString(), any());
    }

    @Test
    void 폴러_제출이_실패하면_세션을_ABORTED로_정리하고_AI세션도_중단한다() {
        // @Async 태스크 제출 자체가 실패하는(예: 종료 중 거부) 경우. 세션은 이미
        // IN_PROGRESS 로 저장됐으므로 정리하지 않으면 영구 잔류한다.
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_p", "task_p", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_p"));
        doThrow(new org.springframework.core.task.TaskRejectedException("executor shutting down"))
                .when(firstQuestionPoller).pollAndDeliver("sess_p", "task_p", 6);

        assertThatThrownBy(() -> service.start(USER_ID, request(null)))
                .isInstanceOf(RuntimeException.class);

        verify(aiClient).abortSession("sess_p");
        verify(sessionWriter).markAborted("sess_p");
    }

    // ───────────────────────── 재면접(replay) ─────────────────────────

    private static final String SOURCE_SESSION_ID = "sess_root_1";

    private InterviewStartRequest replayRequest(String sourceSessionId) {
        return new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), null, "백엔드 개발", Persona.PRESSURE, 6, null,
                sourceSessionId);
    }

    /** 완료 상태의 root 세션. 기본 옵션은 request(null) 과 동일해 재면접이 유지됩니다. */
    private InterviewSession completedRoot(String sessionId) {
        return InterviewSession.builder()
                .sessionId(sessionId)
                .user(user)
                .document(document)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(6)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(SessionStatus.COMPLETED)
                .build();
    }

    private List<AiReplayLogItem> sampleReplayLog() {
        return List.of(
                AiReplayLogItem.question("지원 동기를 말씀해 주세요.", "지원동기", "L1", false),
                AiReplayLogItem.followup("L2"));
    }

    @Test
    void 일반_면접은_AI_요청에_retry_of_session_id_와_replay_log_가_모두_null_이다() {
        // 회귀: sourceSessionId 가 없으면 재면접 조회·조립을 전혀 하지 않는다.
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_n", "task_n", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_n"));

        service.start(USER_ID, request(null));

        ArgumentCaptor<AiSessionStartRequest> captor = ArgumentCaptor.forClass(AiSessionStartRequest.class);
        verify(aiClient).startSession(captor.capture());
        assertThat(captor.getValue().retryOfSessionId()).isNull();
        assertThat(captor.getValue().replayLog()).isNull();
        // 재면접 경로를 타지 않았으므로 조회/조립이 호출되지 않는다.
        verify(sessionQueryService, never()).getOwnedSession(anyString(), anyString());
        verify(replayLogAssembler, never()).assemble(anyString());
        // 일반 세션이라 retryOfSession 은 null 로 저장된다.
        verify(sessionWriter).createSession(any(), any(), any(), any(), any(), eq(6), eq(null));
    }

    @Test
    void 정상_재면접은_root_세션_ID_와_replay_log_를_AI_에_보내고_연관을_저장한다() {
        InterviewSession root = completedRoot(SOURCE_SESSION_ID);
        when(sessionQueryService.getOwnedSession(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        when(sessionQueryService.getRootForReplay(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        when(replayLogAssembler.assemble(SOURCE_SESSION_ID)).thenReturn(sampleReplayLog());
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_r2", "task_r2", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_r2"));

        service.start(USER_ID, replayRequest(SOURCE_SESSION_ID));

        ArgumentCaptor<AiSessionStartRequest> captor = ArgumentCaptor.forClass(AiSessionStartRequest.class);
        verify(aiClient).startSession(captor.capture());
        assertThat(captor.getValue().retryOfSessionId()).isEqualTo(SOURCE_SESSION_ID);
        assertThat(captor.getValue().replayLog()).containsExactlyElementsOf(sampleReplayLog());
        // 재면접이 실제 적용됐으므로 root 세션을 연관으로 저장한다.
        verify(sessionWriter).createSession(any(), any(), any(), any(), any(), eq(6), eq(root));
        // root 는 fetch-join 조회(getRootForReplay)로 읽어야 트랜잭션 밖 옵션 비교에서
        // LazyInitializationException 이 나지 않는다. 이 경로를 고정한다.
        verify(sessionQueryService).getRootForReplay(SOURCE_SESSION_ID, USER_ID);
    }

    @Test
    void 정상_재면접이면_응답의_replayApplied_가_true_다() {
        InterviewSession root = completedRoot(SOURCE_SESSION_ID);
        when(sessionQueryService.getOwnedSession(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        when(sessionQueryService.getRootForReplay(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        when(replayLogAssembler.assemble(SOURCE_SESSION_ID)).thenReturn(sampleReplayLog());
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_ra", "task_ra", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_ra"));

        InterviewStartResponse response = service.start(USER_ID, replayRequest(SOURCE_SESSION_ID));

        assertThat(response.replayApplied()).isTrue();
    }

    @Test
    void 재면접의_재면접이면_직전이_아니라_최초_root_세션을_retryOfSessionId_로_보낸다() {
        // source 는 2회차(retryOfSession=root), root 는 1회차.
        InterviewSession root = completedRoot("sess_root_origin");
        InterviewSession second = InterviewSession.builder()
                .sessionId("sess_second")
                .user(user)
                .document(document)
                .retryOfSession(root)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(6)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(SessionStatus.COMPLETED)
                .build();
        when(sessionQueryService.getOwnedSession("sess_second", USER_ID)).thenReturn(second);
        when(sessionQueryService.getRootForReplay("sess_root_origin", USER_ID)).thenReturn(root);
        when(replayLogAssembler.assemble("sess_root_origin")).thenReturn(sampleReplayLog());
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_third", "task_3", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6), any()))
                .thenReturn(fakeSession("sess_third"));

        service.start(USER_ID, replayRequest("sess_second"));

        ArgumentCaptor<AiSessionStartRequest> captor = ArgumentCaptor.forClass(AiSessionStartRequest.class);
        verify(aiClient).startSession(captor.capture());
        // 직전 회차(sess_second)가 아니라 최초 root(sess_root_origin)여야 한다.
        assertThat(captor.getValue().retryOfSessionId()).isEqualTo("sess_root_origin");
        verify(replayLogAssembler).assemble("sess_root_origin");
        verify(sessionWriter).createSession(any(), any(), any(), any(), any(), eq(6), eq(root));
    }

    @Test
    void 고정_옵션이_다른_재면접_요청은_400으로_거부하고_AI_호출_세션저장을_하지_않는다() {
        InterviewSession root = completedRoot(SOURCE_SESSION_ID);
        when(sessionQueryService.getOwnedSession(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        when(sessionQueryService.getRootForReplay(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        // 정책이 불일치로 INVALID_REQUEST 를 던지도록 강제(void 메서드라 doThrow 사용).
        doThrow(new BusinessException(ErrorCode.INVALID_REQUEST, "조건이 다릅니다"))
                .when(replayOptionPolicy)
                .enforceFixedOptions(any(), anyInt(), any(), any(), any());

        assertThatThrownBy(() -> service.start(USER_ID, replayRequest(SOURCE_SESSION_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);

        // 옵션 불일치면 AI 호출·세션 저장·조립을 모두 하지 않는다.
        verify(aiClient, never()).startSession(any());
        verify(sessionWriter, never()).createSession(any(), any(), any(), any(), any(), anyInt(), any());
        verify(replayLogAssembler, never()).assemble(anyString());
        verify(firstQuestionPoller, never()).pollAndDeliver(anyString(), anyString(), any());
    }

    @Test
    void documentId_가_다르면_400으로_거부한다() {
        assertReplayRejectedWhenPolicyThrows();
    }

    @Test
    void companyId_가_다르면_400으로_거부한다() {
        assertReplayRejectedWhenPolicyThrows();
    }

    @Test
    void jobRole_이_다르면_400으로_거부한다() {
        assertReplayRejectedWhenPolicyThrows();
    }

    @Test
    void questionCount_가_다르면_400으로_거부한다() {
        assertReplayRejectedWhenPolicyThrows();
    }

    @Test
    void persona_가_다르면_400으로_거부한다() {
        assertReplayRejectedWhenPolicyThrows();
    }

    /**
     * 각 고정 옵션(문서·기업·직무·문항수·페르소나) 불일치는 ReplayOptionPolicy 가 던지는
     * INVALID_REQUEST 로 수렴한다. 옵션별 비교 로직 자체는 ReplayOptionPolicyTest 가 검증하고,
     * 여기서는 서비스가 그 예외를 그대로 전파해 거부하는지만 확인한다.
     */
    private void assertReplayRejectedWhenPolicyThrows() {
        InterviewSession root = completedRoot(SOURCE_SESSION_ID);
        when(sessionQueryService.getOwnedSession(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        when(sessionQueryService.getRootForReplay(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        doThrow(new BusinessException(ErrorCode.INVALID_REQUEST, "조건이 다릅니다"))
                .when(replayOptionPolicy)
                .enforceFixedOptions(any(), anyInt(), any(), any(), any());

        assertThatThrownBy(() -> service.start(USER_ID, replayRequest(SOURCE_SESSION_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(aiClient, never()).startSession(any());
        verify(sessionWriter, never()).createSession(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void 존재하지_않거나_타인_세션이면_SESSION_NOT_FOUND_로_거부하고_AI_를_호출하지_않는다() {
        when(sessionQueryService.getOwnedSession(SOURCE_SESSION_ID, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> service.start(USER_ID, replayRequest(SOURCE_SESSION_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        verify(aiClient, never()).startSession(any());
        verify(firstQuestionPoller, never()).pollAndDeliver(anyString(), anyString(), any());
    }

    @Test
    void 완료되지_않은_세션으로는_재면접할_수_없다() {
        InterviewSession inProgress = fakeSession(SOURCE_SESSION_ID); // IN_PROGRESS
        when(sessionQueryService.getOwnedSession(SOURCE_SESSION_ID, USER_ID)).thenReturn(inProgress);

        assertThatThrownBy(() -> service.start(USER_ID, replayRequest(SOURCE_SESSION_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_COMPLETED);

        verify(aiClient, never()).startSession(any());
    }

    @Test
    void root_질문_로그가_비어_있으면_일반_면접으로_전환하지_않고_오류를_낸다() {
        InterviewSession root = completedRoot(SOURCE_SESSION_ID);
        when(sessionQueryService.getOwnedSession(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        when(sessionQueryService.getRootForReplay(SOURCE_SESSION_ID, USER_ID)).thenReturn(root);
        // 조립기가 유효한 QUESTION 없음으로 INTERNAL_ERROR(저장 데이터 무결성 문제)를 던진다.
        when(replayLogAssembler.assemble(SOURCE_SESSION_ID))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR));

        assertThatThrownBy(() -> service.start(USER_ID, replayRequest(SOURCE_SESSION_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);

        verify(aiClient, never()).startSession(any());
    }

    @Test
    void root_세션이_타인_소유면_SESSION_NOT_FOUND_로_차단한다() {
        // source 는 소유하지만(2회차), root(1회차)가 다른 사용자 소유인 교차 참조 상황.
        // getRootForReplay 가 userId 조건으로 걸러 빈 값을 돌려주면 SESSION_NOT_FOUND.
        InterviewSession second = InterviewSession.builder()
                .sessionId("sess_second")
                .user(user)
                .document(document)
                .retryOfSession(completedRoot("sess_root_other"))
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(6)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(SessionStatus.COMPLETED)
                .build();
        when(sessionQueryService.getOwnedSession("sess_second", USER_ID)).thenReturn(second);
        when(sessionQueryService.getRootForReplay("sess_root_other", USER_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> service.start(USER_ID, replayRequest("sess_second")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        verify(aiClient, never()).startSession(any());
    }
}
