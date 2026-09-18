package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.dto.request.AnswerSubmitRequest;
import com.cuea.domain.interview.dto.request.AnswerUploadUrlRequest;
import com.cuea.domain.interview.dto.response.AnswerUploadUrlResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.infrastructure.ai.AiClient;
import com.cuea.infrastructure.ai.dto.AiAnswerSubmitRequest;
import com.cuea.infrastructure.file.FileValidator;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import com.cuea.infrastructure.file.S3StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 답변 제출 REST 동기 구간을 검증합니다.
 *
 * <p>여기서 검증: 소유권·상태 확인 → questionId 소속 확인 → object key namespace 검증
 * → S3 존재 확인 → object key 저장 → AI 접근용 presigned GET URL 생성 → AI 제출 →
 * 백그라운드 폴러 위임 → 즉시 반환. <b>폴링·다음 질문 저장·push 는 이 서비스가 하지
 * 않고</b> {@link InterviewAnswerPoller} 에 위임하므로, 그 위임 호출만 확인하고 폴링
 * 자체는 {@code InterviewAnswerPollerTest} 에서 검증합니다.
 */
class InterviewAnswerServiceTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "sess_1";
    private static final String QUESTION_ID = "q_1";

    private InterviewSessionRepository sessionRepository;
    private QuestionRepository questionRepository;
    private InterviewSessionWriter sessionWriter;
    private FileValidator fileValidator;
    private PresignedUrlIssuer presignedUrlIssuer;
    private S3StorageService storageService;
    private AiClient aiClient;
    private InterviewAnswerPoller answerPoller;
    private InterviewAnswerService service;

    private User user;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        questionRepository = mock(QuestionRepository.class);
        sessionWriter = mock(InterviewSessionWriter.class);
        fileValidator = new FileValidator();   // 순수 검증 로직이라 실제 객체를 씁니다.
        presignedUrlIssuer = mock(PresignedUrlIssuer.class);
        storageService = mock(S3StorageService.class);
        aiClient = mock(AiClient.class);
        answerPoller = mock(InterviewAnswerPoller.class);

        user = User.create("kim@example.com", "김취준");

        when(sessionRepository.findBySessionIdAndUser_UserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(inProgressSession()));
        // 기본값: 질문이 존재하고(C2), S3 객체도 업로드돼 있다(C8). 개별 테스트에서 덮어씀.
        when(questionRepository.findBySessionIdAndQuestionId(SESSION_ID, QUESTION_ID))
                .thenReturn(Optional.of(existingQuestion()));
        when(storageService.exists(anyString())).thenReturn(true);

        service = new InterviewAnswerService(
                sessionRepository, questionRepository, sessionWriter, fileValidator,
                presignedUrlIssuer, storageService, aiClient, answerPoller);
    }

    private Question existingQuestion() {
        return Question.builder()
                .sessionId(SESSION_ID).questionId(QUESTION_ID).type(QuestionType.QUESTION)
                .text("질문").questionNumber(1).topicIndex(0).build();
    }

    private InterviewSession inProgressSession() {
        return InterviewSession.builder()
                .sessionId(SESSION_ID)
                .user(user)
                .document(mock(Document.class))
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(9)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(SessionStatus.IN_PROGRESS)
                .build();
    }

    // ── 업로드 URL 발급 ─────────────────────────────────────────

    @Test
    void 답변_audio_video_PUT_URL_을_함께_발급한다() {
        when(presignedUrlIssuer.issueUpload(eq("sessions/sess_1/answers/q_1.webm"), eq("audio/webm")))
                .thenReturn("https://s3/put/audio");
        when(presignedUrlIssuer.issueUpload(eq("sessions/sess_1/answers/q_1_video.mp4"), eq("video/mp4")))
                .thenReturn("https://s3/put/video");

        AnswerUploadUrlResponse res = service.issueUploadUrls(USER_ID, SESSION_ID,
                new AnswerUploadUrlRequest(QUESTION_ID,
                        "ans.webm", "audio/webm", 1_000L,
                        "ans.mp4", "video/mp4", 2_000L));

        assertThat(res.audioObjectKey()).isEqualTo("sessions/sess_1/answers/q_1.webm");
        assertThat(res.audioUploadUrl()).isEqualTo("https://s3/put/audio");
        // video object key 는 audio 와 겹치지 않게 _video 접미사를 붙인다.
        assertThat(res.videoObjectKey()).isEqualTo("sessions/sess_1/answers/q_1_video.mp4");
        assertThat(res.videoUploadUrl()).isEqualTo("https://s3/put/video");
    }

    @Test
    void 영상이_없으면_audio_URL_만_발급하고_video_는_null_이다() {
        when(presignedUrlIssuer.issueUpload(eq("sessions/sess_1/answers/q_1.webm"), eq("audio/webm")))
                .thenReturn("https://s3/put/audio");

        AnswerUploadUrlResponse res = service.issueUploadUrls(USER_ID, SESSION_ID,
                new AnswerUploadUrlRequest(QUESTION_ID,
                        "ans.webm", "audio/webm", 1_000L,
                        null, null, null));

        assertThat(res.audioUploadUrl()).isEqualTo("https://s3/put/audio");
        assertThat(res.videoObjectKey()).isNull();
        assertThat(res.videoUploadUrl()).isNull();
        // 영상 PUT 은 발급하지 않는다(video/mp4 로 호출된 적 없음).
        verify(presignedUrlIssuer, never()).issueUpload(anyString(), eq("video/mp4"));
    }

    @Test
    void 소유하지_않은_세션에는_업로드_URL_을_발급하지_않는다() {
        when(sessionRepository.findBySessionIdAndUser_UserId(SESSION_ID, "other"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueUploadUrls("other", SESSION_ID,
                new AnswerUploadUrlRequest(QUESTION_ID, "ans.webm", "audio/webm", 1_000L, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void video_에는_video_MIME_만_허용한다_audio_MIME_은_거부() {
        when(presignedUrlIssuer.issueUpload(eq("sessions/sess_1/answers/q_1.webm"), eq("audio/webm")))
                .thenReturn("https://s3/put/audio");

        // 영상 파일에 audio/mp4 를 주면 확장자-MIME 불일치로 거부.
        assertThatThrownBy(() -> service.issueUploadUrls(USER_ID, SESSION_ID,
                new AnswerUploadUrlRequest(QUESTION_ID,
                        "ans.webm", "audio/webm", 1_000L,
                        "ans.mp4", "audio/mp4", 2_000L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    void audio_에는_audio_MIME_만_허용한다_video_MIME_은_거부() {
        // 오디오 자리에 video/webm 을 주면 거부(audio↔video MIME 분리).
        assertThatThrownBy(() -> service.issueUploadUrls(USER_ID, SESSION_ID,
                new AnswerUploadUrlRequest(QUESTION_ID,
                        "ans.webm", "video/webm", 1_000L, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    // ── 답변 제출 ───────────────────────────────────────────────

    @Test
    void 제출된_object_key_를_질문에_저장하고_presigned_GET_URL_로_바꿔_AI_에_전달한다() {
        when(presignedUrlIssuer.issueRecordingDownload("sessions/sess_1/answers/q_1.webm"))
                .thenReturn("https://s3/get/audio");
        when(presignedUrlIssuer.issueRecordingDownload("sessions/sess_1/answers/q_1_video.mp4"))
                .thenReturn("https://s3/get/video");
        when(aiClient.submitAnswer(eq(SESSION_ID), any())).thenReturn("task_ans_1");

        service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm",
                "sessions/sess_1/answers/q_1_video.mp4", false));

        // object key 는 DB 에 저장(presigned URL 이 아니라 key 만).
        verify(sessionWriter).attachAnswerMedia(SESSION_ID, QUESTION_ID,
                "sessions/sess_1/answers/q_1.webm", "sessions/sess_1/answers/q_1_video.mp4", false);

        // AI 에는 object key 가 아니라 presigned GET URL 을 전달.
        ArgumentCaptor<AiAnswerSubmitRequest> captor = ArgumentCaptor.forClass(AiAnswerSubmitRequest.class);
        verify(aiClient).submitAnswer(eq(SESSION_ID), captor.capture());
        AiAnswerSubmitRequest sent = captor.getValue();
        assertThat(sent.questionId()).isEqualTo(QUESTION_ID);
        assertThat(sent.audioUrl()).isEqualTo("https://s3/get/audio");
        assertThat(sent.videoUrl()).isEqualTo("https://s3/get/video");
        assertThat(sent.isTimeout()).isFalse();
    }

    @Test
    void object_key_저장이_AI_호출보다_먼저_일어난다() {
        when(presignedUrlIssuer.issueRecordingDownload(anyString())).thenReturn("https://s3/get");
        when(aiClient.submitAnswer(eq(SESSION_ID), any())).thenReturn("task_ans_1");

        service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false));

        var order = inOrder(sessionWriter, aiClient);
        order.verify(sessionWriter).attachAnswerMedia(eq(SESSION_ID), eq(QUESTION_ID),
                anyString(), any(), anyBoolean());
        order.verify(aiClient).submitAnswer(eq(SESSION_ID), any());
    }

    @Test
    void video_가_없으면_video_url_은_null_로_AI_에_전달한다() {
        when(presignedUrlIssuer.issueRecordingDownload("sessions/sess_1/answers/q_1.webm"))
                .thenReturn("https://s3/get/audio");
        when(aiClient.submitAnswer(eq(SESSION_ID), any())).thenReturn("task_ans_1");

        service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false));

        ArgumentCaptor<AiAnswerSubmitRequest> captor = ArgumentCaptor.forClass(AiAnswerSubmitRequest.class);
        verify(aiClient).submitAnswer(eq(SESSION_ID), captor.capture());
        assertThat(captor.getValue().videoUrl()).isNull();
        // 영상 object key 로는 presigned GET URL 을 만들지 않는다.
        verify(presignedUrlIssuer, never())
                .issueRecordingDownload("sessions/sess_1/answers/q_1_video.mp4");
        // DB 에도 video object key 는 null 로 저장.
        verify(sessionWriter).attachAnswerMedia(SESSION_ID, QUESTION_ID,
                "sessions/sess_1/answers/q_1.webm", null, false);
    }

    @Test
    void timeout_답변은_is_timeout_true_로_저장하고_AI_에_전달한다() {
        when(presignedUrlIssuer.issueRecordingDownload(anyString())).thenReturn("https://s3/get");
        when(aiClient.submitAnswer(eq(SESSION_ID), any())).thenReturn("task_ans_1");

        service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, true));

        verify(sessionWriter).attachAnswerMedia(SESSION_ID, QUESTION_ID,
                "sessions/sess_1/answers/q_1.webm", null, true);
        ArgumentCaptor<AiAnswerSubmitRequest> captor = ArgumentCaptor.forClass(AiAnswerSubmitRequest.class);
        verify(aiClient).submitAnswer(eq(SESSION_ID), captor.capture());
        assertThat(captor.getValue().isTimeout()).isTrue();
    }

    @Test
    void 제출_REST_는_폴링을_기다리지_않고_백그라운드_폴러에_위임한다() {
        when(presignedUrlIssuer.issueRecordingDownload(anyString())).thenReturn("https://s3/get");
        when(aiClient.submitAnswer(eq(SESSION_ID), any())).thenReturn("task_ans_1");

        service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false));

        // 폴링/다음 질문 저장은 이 서비스가 직접 하지 않고 폴러에 위임한다.
        verify(answerPoller).pollAndDeliver(SESSION_ID, "task_ans_1");
        verify(sessionWriter, never()).saveNextQuestion(anyString(), any());
        verify(sessionWriter, never()).completeSession(anyString());
    }

    @Test
    void AI_가_task_id_를_주지_않으면_세션을_정리하고_폴러에_위임하지_않는다() {
        when(presignedUrlIssuer.issueRecordingDownload(anyString())).thenReturn("https://s3/get");
        when(aiClient.submitAnswer(eq(SESSION_ID), any())).thenReturn(null);

        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNEXPECTED_AI_RESPONSE);

        // task_id 미수신은 계약 위반. AI/Backend 상태 동기화 보장 불가라 세션 정리.
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
        verify(answerPoller, never()).pollAndDeliver(anyString(), anyString());
    }

    @Test
    void 종료된_세션에는_답변을_제출할_수_없다() {
        InterviewSession completed = inProgressSession();
        completed.complete();
        when(sessionRepository.findBySessionIdAndUser_UserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_ENDED);

        verify(aiClient, never()).submitAnswer(anyString(), any());
        verify(answerPoller, never()).pollAndDeliver(anyString(), anyString());
    }

    @Test
    void AI_요청_JSON_이_question_id_audio_url_video_url_is_timeout_계약과_일치한다() throws Exception {
        // 계약 정합성: @AiJson(snake_case) 직렬화 결과가 AI 최종 계약 필드명과 일치해야 한다.
        AiAnswerSubmitRequest req = new AiAnswerSubmitRequest(
                "q_1", "https://s3/get/audio", "https://s3/get/video", false);
        String json = new ObjectMapper().writeValueAsString(req);

        assertThat(json).contains("\"question_id\":\"q_1\"");
        assertThat(json).contains("\"audio_url\":\"https://s3/get/audio\"");
        assertThat(json).contains("\"video_url\":\"https://s3/get/video\"");
        assertThat(json).contains("\"is_timeout\":false");
        // camelCase 가 새어나가면 안 된다.
        assertThat(json).doesNotContain("questionId", "audioUrl", "videoUrl", "isTimeout");
    }

    @Test
    void video_가_null_이면_AI_요청_JSON_에서_video_url_은_null_로_나간다() throws Exception {
        // 계약이 명시적으로 null 을 허용하므로 키를 빼지 않고 null 로 보낸다.
        AiAnswerSubmitRequest req = new AiAnswerSubmitRequest(
                "q_1", "https://s3/get/audio", null, false);
        String json = new ObjectMapper().writeValueAsString(req);

        assertThat(json).contains("\"video_url\":null");
    }

    // ── object key 신뢰 경계 ─────────────────────────────────────

    @Test
    void 다른_세션의_object_key_로는_presigned_GET_을_발급받을_수_없다() {
        // sess_1 세션에 sess_OTHER 의 key 를 보내면 거부해야 한다.
        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_OTHER/answers/q_1.webm", null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(sessionWriter, never()).attachAnswerMedia(anyString(), anyString(), anyString(), any(), anyBoolean());
        verify(presignedUrlIssuer, never()).issueRecordingDownload(anyString());
        verify(aiClient, never()).submitAnswer(anyString(), any());
    }

    @Test
    void 다른_질문의_object_key_로는_presigned_GET_을_발급받을_수_없다() {
        // 요청은 q_1 인데 key 는 q_2 를 가리키면 거부.
        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_2.webm", null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(aiClient, never()).submitAnswer(anyString(), any());
    }

    @Test
    void 임의의_object_key_예_이력서_경로_로는_presigned_GET_을_발급받을_수_없다() {
        // resumes/ 등 답변 namespace 밖의 임의 key 는 거부(다른 유저 파일 탈취 방지).
        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "resumes/other-user/1.pdf", null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(presignedUrlIssuer, never()).issueRecordingDownload(anyString());
    }

    @Test
    void 잘못된_video_object_key_는_거부하지만_video_null_은_허용한다() {
        // video 가 다른 질문/세션을 가리키면 거부.
        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm",
                "sessions/sess_1/answers/q_2_video.mp4", false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);

        // video=null 은 정상 통과(별도 검증은 다른 테스트에서 확인).
        when(presignedUrlIssuer.issueRecordingDownload("sessions/sess_1/answers/q_1.webm"))
                .thenReturn("https://s3/get/audio");
        when(aiClient.submitAnswer(eq(SESSION_ID), any())).thenReturn("task_ans_1");

        service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false));

        verify(answerPoller).pollAndDeliver(SESSION_ID, "task_ans_1");
    }

    // ── C2: upload-urls questionId 소속 검증 ─────────────────────

    @Test
    void 존재하지_않는_questionId_로는_업로드_URL_을_발급하지_않는다() {
        when(questionRepository.findBySessionIdAndQuestionId(SESSION_ID, "q_bogus"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueUploadUrls(USER_ID, SESSION_ID,
                new AnswerUploadUrlRequest("q_bogus", "ans.webm", "audio/webm", 1_000L, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.QUESTION_NOT_FOUND);

        // 질문이 없으면 PUT URL 을 만들지 않는다(orphan 객체 방지).
        verify(presignedUrlIssuer, never()).issueUpload(anyString(), anyString());
    }

    // ── C8: S3 업로드 완료 확인 ─────────────────────────────────

    @Test
    void 업로드되지_않은_audio_key_는_AI_호출_전에_UPLOAD_NOT_COMPLETED_로_거부한다() {
        when(storageService.exists("sessions/sess_1/answers/q_1.webm")).thenReturn(false);

        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_NOT_COMPLETED);

        verify(sessionWriter, never()).attachAnswerMedia(anyString(), anyString(), anyString(), any(), anyBoolean());
        verify(aiClient, never()).submitAnswer(anyString(), any());
    }

    @Test
    void 업로드되지_않은_video_key_도_거부한다() {
        when(storageService.exists("sessions/sess_1/answers/q_1.webm")).thenReturn(true);
        when(storageService.exists("sessions/sess_1/answers/q_1_video.mp4")).thenReturn(false);

        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm",
                "sessions/sess_1/answers/q_1_video.mp4", false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_NOT_COMPLETED);

        verify(aiClient, never()).submitAnswer(anyString(), any());
    }

    // ── C9: 세션 상태 구분 ───────────────────────────────────────

    @Test
    void ABORTED_세션에는_SESSION_ABORTED_로_거부한다() {
        InterviewSession aborted = inProgressSession();
        aborted.abort();
        when(sessionRepository.findBySessionIdAndUser_UserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(aborted));

        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_ABORTED);
    }

    // ── C4: async poller 제출 실패 시 cleanup ────────────────────

    @Test
    void 폴러_제출이_실패하면_AI세션_abort_하고_세션을_ABORTED로_정리한다() {
        when(presignedUrlIssuer.issueRecordingDownload(anyString())).thenReturn("https://s3/get");
        when(aiClient.submitAnswer(eq(SESSION_ID), any())).thenReturn("task_ans_1");
        // @Async 태스크 제출 자체가 거부되는(종료 중 등) 경우. AI task 는 이미 수락됐다.
        doThrow(new org.springframework.core.task.TaskRejectedException("executor shutting down"))
                .when(answerPoller).pollAndDeliver(SESSION_ID, "task_ans_1");

        assertThatThrownBy(() -> service.submit(USER_ID, SESSION_ID, new AnswerSubmitRequest(
                QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false)))
                .isInstanceOf(RuntimeException.class);

        // AI task 가 방치되지 않도록 정리한다.
        verify(aiClient).abortSession(SESSION_ID);
        verify(sessionWriter).markAborted(SESSION_ID);
    }

    // ── C7: isTimeout 필수 (bean validation) ─────────────────────

    @Test
    void isTimeout_이_null_이면_bean_validation_이_거부한다() {
        // 컨트롤러의 @Valid 가 적용하는 것과 동일한 검증. JSON 에서 is_timeout 누락 시
        // isTimeout=null 이 되고, @NotNull 이 이를 거부해야 한다(false 로 조용히 통과 금지).
        try (jakarta.validation.ValidatorFactory factory =
                     jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            jakarta.validation.Validator validator = factory.getValidator();

            AnswerSubmitRequest missing = new AnswerSubmitRequest(
                    QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, null);
            var violations = validator.validate(missing);
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("isTimeout"));

            // false / true 는 정상 값(위반 없음).
            assertThat(validator.validate(new AnswerSubmitRequest(
                    QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, false))).isEmpty();
            assertThat(validator.validate(new AnswerSubmitRequest(
                    QUESTION_ID, "sessions/sess_1/answers/q_1.webm", null, true))).isEmpty();
        }
    }
}
