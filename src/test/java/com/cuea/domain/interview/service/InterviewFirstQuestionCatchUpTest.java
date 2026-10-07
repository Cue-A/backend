package com.cuea.domain.interview.service;

import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import com.cuea.infrastructure.websocket.message.SocketMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket 연결 시점 첫 질문 복구(catch-up)를 검증합니다. (Issue #35)
 *
 * <p>핵심은 "유실 방지" 와 "잘못된 replay 방지" 를 동시에 만족하는 것입니다.
 * <ul>
 *   <li>첫 질문이 저장돼 있고 아직 답변 전이면 복구한다(연결이 늦어 push 가 드롭된 경우).</li>
 *   <li>아직 질문이 없으면(폴링 미완료) 복구하지 않는다.</li>
 *   <li>이미 답변이 진행됐으면(이후 질문 존재 / 첫 질문에 답변 오디오) 복구하지 않는다.</li>
 *   <li>세션이 COMPLETED/ABORTED 면 복구하지 않는다.</li>
 * </ul>
 * 폴링 push 와 동일하게 {@link QuestionPushFactory} 로 메시지를 조립하므로 payload
 * 계약(음성 presign 포함)은 여기서 다시 검증하지 않고 factory 호출 위임만 확인합니다.
 */
class InterviewFirstQuestionCatchUpTest {

    private static final String SESSION_ID = "sess_1";

    private InterviewSessionRepository sessionRepository;
    private QuestionRepository questionRepository;
    private QuestionPushFactory questionPushFactory;
    private InterviewFirstQuestionCatchUp catchUp;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        questionRepository = mock(QuestionRepository.class);
        questionPushFactory = mock(QuestionPushFactory.class);
        catchUp = new InterviewFirstQuestionCatchUp(
                sessionRepository, questionRepository, questionPushFactory);
    }

    private InterviewSession session(SessionStatus status, int questionCount) {
        return InterviewSession.builder()
                .sessionId(SESSION_ID)
                .mode("PRACTICE")
                .jobRole("백엔드")
                .questionCount(questionCount)
                .persona(com.cuea.domain.interview.entity.Persona.FRIENDLY)
                .hideQuestionText(false)
                .status(status)
                .build();
    }

    private Question firstQuestion(String answerAudioObjectKey) {
        Question q = Question.builder()
                .sessionId(SESSION_ID).questionId("q_1")
                .type(QuestionType.QUESTION).text("지원 동기를 말씀해 주세요.")
                .audioUrl("https://s3.../q_1.mp3").category("지원동기").difficulty("L1")
                .questionNumber(1).topicIndex(0).build();
        if (answerAudioObjectKey != null) {
            q.attachAnswerAudio(answerAudioObjectKey);
        }
        return q;
    }

    @Test
    void 첫질문이_저장돼_있고_아직_답변_전이면_연결_시점에_복구한다() {
        // 연결이 폴링보다 늦어 push 가 드롭된 상황. DB 에는 첫 질문이 저장돼 있다.
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.of(session(SessionStatus.IN_PROGRESS, 9)));
        Question first = firstQuestion(null);
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .thenReturn(List.of(first));
        when(questionPushFactory.create(first, 9)).thenReturn(new QuestionPushMessage(
                "q_1", "QUESTION", "지원 동기를 말씀해 주세요.",
                "https://s3.../q_1.mp3?X-Amz-Signature=abc", true,
                "지원동기", "L1", 1, 9));

        Optional<InterviewFirstQuestionCatchUp.FirstQuestion> result = catchUp.firstQuestion(SESSION_ID);

        // 폴링 push 와 동일한 factory 로 조립한다(계약 동일).
        verify(questionPushFactory).create(first, 9);
        assertThat(result).isPresent();
        // questionId 는 소켓별 중복 전송 방지 클레임 키로 쓰인다.
        assertThat(result.get().questionId()).isEqualTo("q_1");
        SocketMessage<?> message = result.get().message();
        assertThat(message.type()).isEqualTo("question");
        QuestionPushMessage payload = (QuestionPushMessage) message.payload();
        assertThat(payload.questionId()).isEqualTo("q_1");
        assertThat(payload.questionTotal()).isEqualTo(9);
        assertThat(payload.audioUrl()).contains("X-Amz-Signature");
    }

    @Test
    void 아직_질문이_없으면_복구하지_않는다() {
        // 폴링이 첫 질문을 아직 저장하지 못했다. 유실이 아니므로 복구할 게 없다.
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.of(session(SessionStatus.IN_PROGRESS, 9)));
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .thenReturn(List.of());

        Optional<InterviewFirstQuestionCatchUp.FirstQuestion> result = catchUp.firstQuestion(SESSION_ID);

        assertThat(result).isEmpty();
        verify(questionPushFactory, never()).create(any(), anyInt());
    }

    @Test
    void 이미_다음_질문이_있으면_과거_첫질문을_복구하지_않는다() {
        // 답변이 진행돼 두 번째 질문이 생겼다. 프론트는 이미 첫 질문을 받았으므로
        // 과거 첫 질문을 다시 내려보내면 안 된다(잘못된 replay 방지).
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.of(session(SessionStatus.IN_PROGRESS, 9)));
        Question first = firstQuestion(null);
        Question second = Question.builder()
                .sessionId(SESSION_ID).questionId("q_2")
                .type(QuestionType.QUESTION).text("두 번째 질문")
                .questionNumber(2).topicIndex(1).build();
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .thenReturn(List.of(first, second));

        Optional<InterviewFirstQuestionCatchUp.FirstQuestion> result = catchUp.firstQuestion(SESSION_ID);

        assertThat(result).isEmpty();
        verify(questionPushFactory, never()).create(any(), anyInt());
    }

    @Test
    void 첫질문에_이미_답변이_붙어_있으면_복구하지_않는다() {
        // 첫 질문 하나뿐이지만 답변 오디오가 붙어 있다 = 사용자가 이미 답변을 올렸다.
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.of(session(SessionStatus.IN_PROGRESS, 9)));
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .thenReturn(List.of(firstQuestion("sessions/sess_1/answers/q_1.webm")));

        Optional<InterviewFirstQuestionCatchUp.FirstQuestion> result = catchUp.firstQuestion(SESSION_ID);

        assertThat(result).isEmpty();
        verify(questionPushFactory, never()).create(any(), anyInt());
    }

    @Test
    void COMPLETED_세션에는_복구하지_않는다() {
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.of(session(SessionStatus.COMPLETED, 9)));

        Optional<InterviewFirstQuestionCatchUp.FirstQuestion> result = catchUp.firstQuestion(SESSION_ID);

        assertThat(result).isEmpty();
        // 세션 상태만으로 걸러지므로 질문 조회조차 하지 않는다.
        verify(questionRepository, never()).findAllBySessionIdOrderByCreatedAtAsc(any());
        verify(questionPushFactory, never()).create(any(), anyInt());
    }

    @Test
    void ABORTED_세션에는_복구하지_않는다() {
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.of(session(SessionStatus.ABORTED, 9)));

        Optional<InterviewFirstQuestionCatchUp.FirstQuestion> result = catchUp.firstQuestion(SESSION_ID);

        assertThat(result).isEmpty();
        verify(questionRepository, never()).findAllBySessionIdOrderByCreatedAtAsc(any());
        verify(questionPushFactory, never()).create(any(), anyInt());
    }

    @Test
    void 세션이_없으면_복구하지_않는다() {
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.empty());

        Optional<InterviewFirstQuestionCatchUp.FirstQuestion> result = catchUp.firstQuestion(SESSION_ID);

        assertThat(result).isEmpty();
        verify(questionRepository, never()).findAllBySessionIdOrderByCreatedAtAsc(any());
    }

    @Test
    void 첫_결과가_주질문이_아니면_복구하지_않는다() {
        // 세션의 첫 저장 결과가 되묻기 등 questionNumber!=1 이면 첫 주질문이 아니다.
        when(sessionRepository.findBySessionIdForInternal(SESSION_ID))
                .thenReturn(Optional.of(session(SessionStatus.IN_PROGRESS, 9)));
        Question reask = Question.builder()
                .sessionId(SESSION_ID).questionId("q_1r")
                .type(QuestionType.REASK).text("다시 말씀해 주세요.")
                .questionNumber(0).topicIndex(0).build();
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .thenReturn(List.of(reask));

        Optional<InterviewFirstQuestionCatchUp.FirstQuestion> result = catchUp.firstQuestion(SESSION_ID);

        assertThat(result).isEmpty();
        verify(questionPushFactory, never()).create(any(), eq(9));
    }
}
