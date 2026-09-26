package com.cuea.domain.interview.service;

import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 질문 push 메시지 조립 + 질문 음성 presigned GET 발급을 검증합니다. (Issue #41)
 *
 * <p>핵심은 두 가지입니다.
 * <ul>
 *   <li><b>음성 있음</b> — AI 가 준 unsigned URL 을 parsing 하지 않고 계약상 고정 key
 *       ({@code sessions/{sessionId}/questions/{questionId}.mp3})로 presign 해서, 프론트에는
 *       서명 URL 을 내려주고 {@code audioAvailable=true}.</li>
 *   <li><b>음성 없음</b>(TTS_FAILED, {@code audioUrl=null}) — presign 을 시도하지 않고
 *       {@code audioUrl=null}·{@code audioAvailable=false} 로 텍스트만 진행.</li>
 * </ul>
 */
class QuestionPushFactoryTest {

    private static final String SESSION_ID = "sess_1";

    private PresignedUrlIssuer presignedUrlIssuer;
    private QuestionPushFactory factory;

    @BeforeEach
    void setUp() {
        presignedUrlIssuer = mock(PresignedUrlIssuer.class);
        factory = new QuestionPushFactory(presignedUrlIssuer);
    }

    private Question question(String questionId, String audioUrl) {
        return Question.builder()
                .sessionId(SESSION_ID).questionId(questionId)
                .type(QuestionType.QUESTION).text("질문 본문")
                .audioUrl(audioUrl).category("지원동기").difficulty("L1")
                .questionNumber(1).topicIndex(0).build();
    }

    @Test
    void 음성이_있으면_계약된_key_로_presign_해서_서명_URL_을_담고_audioAvailable_true_다() {
        // AI 가 준 unsigned URL 은 존재 여부 판정에만 쓴다. 실제 presign 은 계약 key 로 한다.
        Question q = question("q_1", "https://bucket.s3.amazonaws.com/sessions/sess_1/questions/q_1.mp3");
        // 계약상 고정 key. AI URL parsing 이 아니라 ObjectKeys.questionAudio 로 만든 값.
        String expectedKey = "sessions/sess_1/questions/q_1.mp3";
        when(presignedUrlIssuer.issueQuestionAudioDownload(expectedKey))
                .thenReturn("https://bucket.s3.amazonaws.com/sessions/sess_1/questions/q_1.mp3?X-Amz-Signature=abc");

        QuestionPushMessage message = factory.create(q, 9);

        // 계약 key 로 presign 했는지(=URL parsing 이 아님).
        verify(presignedUrlIssuer).issueQuestionAudioDownload(expectedKey);
        // 프론트에는 서명 URL 이 내려간다. AI 원본 unsigned URL 을 그대로 노출하지 않는다.
        assertThat(message.audioUrl()).contains("X-Amz-Signature");
        assertThat(message.audioUrl()).isNotEqualTo(q.getAudioUrl());
        assertThat(message.audioAvailable()).isTrue();
        assertThat(message.questionId()).isEqualTo("q_1");
        assertThat(message.questionTotal()).isEqualTo(9);
    }

    @Test
    void 음성이_null_이면_presign_을_시도하지_않고_audioUrl_null_audioAvailable_false_다() {
        Question q = question("q_2", null);

        QuestionPushMessage message = factory.create(q, 9);

        // TTS 실패(text-only)는 presign 을 아예 호출하지 않는다.
        verify(presignedUrlIssuer, never()).issueQuestionAudioDownload(anyString());
        assertThat(message.audioUrl()).isNull();
        assertThat(message.audioAvailable()).isFalse();
        // 질문 자체는 정상 전달된다(텍스트만).
        assertThat(message.text()).isEqualTo("질문 본문");
        assertThat(message.questionId()).isEqualTo("q_2");
    }

    @Test
    void presign_이_실패하면_예외를_전파하지_않고_text_only_로_fallback_한다() {
        // Issue #41 정책: 질문 text 는 이미 정상 생성됨. presign 실패만으로 세션을
        // 중단하지 않고 텍스트만 내려준다(audioUrl=null, audioAvailable=false).
        Question q = question("q_3", "https://bucket.s3.amazonaws.com/sessions/sess_1/questions/q_3.mp3");
        String expectedKey = "sessions/sess_1/questions/q_3.mp3";
        // AWS SDK 서명 실패 계열(자격증명·설정 오류 등)은 SdkException 으로 온다.
        when(presignedUrlIssuer.issueQuestionAudioDownload(expectedKey))
                .thenThrow(SdkException.builder().message("presign failed").build());

        // 예외가 전파되지 않아야 한다(assertThatCode 대신 직접 호출해 통과 여부로 검증).
        QuestionPushMessage message = factory.create(q, 9);

        verify(presignedUrlIssuer).issueQuestionAudioDownload(expectedKey);
        // text-only fallback: 음성은 빼고 텍스트만.
        assertThat(message.audioUrl()).isNull();
        assertThat(message.audioAvailable()).isFalse();
        assertThat(message.text()).isEqualTo("질문 본문");
        assertThat(message.questionId()).isEqualTo("q_3");
        assertThat(message.questionTotal()).isEqualTo(9);
    }
}
