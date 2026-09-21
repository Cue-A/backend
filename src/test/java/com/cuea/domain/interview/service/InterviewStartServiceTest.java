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

        PresignedUrlIssuer presignedUrlIssuer = mock(PresignedUrlIssuer.class);
        when(presignedUrlIssuer.issueResumeDownload(anyString()))
                .thenReturn("https://s3.example.com/resume.pdf?presigned=1");

        user = User.create("kim@example.com", "김취준");
        document = document(DocumentStatus.READY);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(documentRepository.findByPublicIdAndUser_UserId(DOCUMENT_PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(document));

        service = new InterviewStartService(
                userRepository, documentRepository, companyRepository,
                new CompanyProfileFormatter(), presignedUrlIssuer,
                aiClient, sessionWriter, firstQuestionPoller);
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

    private InterviewStartRequest request(Long companyId) {
        return new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), companyId, "백엔드 개발", Persona.PRESSURE, 6);
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
        when(sessionWriter.createSession(eq(user), eq(document), eq(null), any(), eq(aiResponse), eq(6)))
                .thenReturn(fakeSession("sess_9f2a1c"));

        InterviewStartResponse response = service.start(USER_ID, request(null));

        assertThat(response.sessionId()).isEqualTo("sess_9f2a1c");
        assertThat(response.questionTotal()).isEqualTo(9);
        // REST 동기 구간에서는 세션 생성까지만 하고, 폴링/첫질문은 백그라운드에 위임한다.
        verify(sessionWriter).createSession(eq(user), eq(document), eq(null), any(), eq(aiResponse), eq(6));
        verify(firstQuestionPoller).pollAndDeliver("sess_9f2a1c", "task_001", 9);
        // 이 서비스는 첫 질문을 직접 저장하지 않는다.
        verify(sessionWriter, never()).saveFirstQuestion(anyString(), any());
    }

    @Test
    void 기업을_선택하면_company_profile_override_와_company_id_문자열을_함께_보낸다() {
        when(companyRepository.findByCompanyIdAndVerifiedTrue(10L)).thenReturn(Optional.of(verifiedCompany()));
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_2", "task_2", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6)))
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
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6)))
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
        when(sessionWriter.createSession(any(), any(), eq(null), any(), any(), eq(6)))
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
    void questionCount_가_null_이면_기본값_6_으로_AI_에_보낸다() {
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_q", "task_q", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6)))
                .thenReturn(fakeSession("sess_q"));

        service.start(USER_ID, new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), null, "백엔드 개발", Persona.FRIENDLY, null));

        ArgumentCaptor<AiSessionStartRequest> captor = ArgumentCaptor.forClass(AiSessionStartRequest.class);
        verify(aiClient).startSession(captor.capture());
        assertThat(captor.getValue().questionCount()).isEqualTo(6);
    }

    @Test
    void questionCount_가_3_6_9_외의_값이면_AI_호출_전에_거부한다() {
        assertThatThrownBy(() -> service.start(USER_ID, new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), null, "백엔드 개발", Persona.FRIENDLY, 4)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUESTION_COUNT);

        // 0 도 마찬가지로 거부(mock 이 session_end 를 첫 결과로 주는 값).
        assertThatThrownBy(() -> service.start(USER_ID, new InterviewStartRequest(
                DOCUMENT_PUBLIC_ID.toString(), null, "백엔드 개발", Persona.FRIENDLY, 0)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUESTION_COUNT);

        verify(aiClient, never()).startSession(any());
        verify(firstQuestionPoller, never()).pollAndDeliver(anyString(), anyString(), any());
    }

    @Test
    void MARKDOWN_문서는_presigned_URL_을_만들_수_없어_AI_호출_전에_거부한다() {
        Document markdown = Document.builder()
                .docId(2L)
                .publicId(DOCUMENT_PUBLIC_ID)
                .user(user)
                .docTitle("대본")
                .docType(DocType.SCRIPT)
                .sourceType(SourceType.MARKDOWN)   // objectKey 없음
                .docText("직접 입력한 본문")
                .status(DocumentStatus.READY)
                .build();
        when(documentRepository.findByPublicIdAndUser_UserId(DOCUMENT_PUBLIC_ID, USER_ID))
                .thenReturn(Optional.of(markdown));

        assertThatThrownBy(() -> service.start(USER_ID, request(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);

        verify(aiClient, never()).startSession(any());
        verify(firstQuestionPoller, never()).pollAndDeliver(anyString(), anyString(), any());
    }

    @Test
    void 로컬_세션_저장이_실패하면_AI_세션을_abort_하고_원본_예외를_던진다() {
        AiSessionStartResponse aiResponse = new AiSessionStartResponse("sess_x", "task_x", 6);
        when(aiClient.startSession(any())).thenReturn(aiResponse);
        BusinessException writeError = new BusinessException(ErrorCode.INTERNAL_ERROR, "DB 저장 실패");
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6))).thenThrow(writeError);

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
        when(sessionWriter.createSession(any(), any(), any(), any(), any(), eq(6)))
                .thenReturn(fakeSession("sess_p"));
        doThrow(new org.springframework.core.task.TaskRejectedException("executor shutting down"))
                .when(firstQuestionPoller).pollAndDeliver("sess_p", "task_p", 6);

        assertThatThrownBy(() -> service.start(USER_ID, request(null)))
                .isInstanceOf(RuntimeException.class);

        verify(aiClient).abortSession("sess_p");
        verify(sessionWriter).markAborted("sess_p");
    }
}
