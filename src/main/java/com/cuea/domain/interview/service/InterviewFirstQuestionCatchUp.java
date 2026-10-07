package com.cuea.domain.interview.service;

import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import com.cuea.infrastructure.websocket.message.SocketMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * WebSocket 연결 시점에 첫 질문 push 유실을 복구합니다. (Issue #35)
 *
 * <p><b>문제:</b> 세션 시작 후 {@link InterviewFirstQuestionPoller} 가 백그라운드로
 * 첫 질문을 저장·push 하는데, AI 가 빠르게 {@code done} 을 주면 프론트가
 * {@code /ws/interviews/{sessionId}} 에 연결하기 전에 push 가 끝나버립니다. 그러면
 * 수신자가 없어 push 가 드롭되고, 질문은 DB 에 저장돼 있지만 프론트는 영영 못 받습니다.
 *
 * <p><b>해결:</b> 연결 직후 DB 를 source of truth 로 삼아, 아직 전달됐어야 할 첫
 * 질문을 그 소켓에만 다시 내려줍니다. 폴링 push 와 완전히 동일한
 * {@link QuestionPushFactory} 로 메시지를 조립하므로 payload 계약(음성 presign 포함,
 * Issue #41)이 바뀌지 않습니다.
 *
 * <h2>중복 방지 (lost vs duplicate)</h2>
 * <p>복구는 <b>세션의 첫 질문 하나</b>({@code questionNumber == 1} 인 주질문)만,
 * 그리고 <b>아직 답변이 시작되지 않았을 때만</b> 돌려줍니다. 답변이 한 번이라도
 * 진행됐다면(이후 질문이 있거나 첫 질문에 답변 미디어가 붙었다면) 프론트는 이미
 * 첫 질문을 받은 것이므로 복구하지 않습니다.
 *
 * <p>폴링 push 와 연결-복구가 같은 소켓에 동시에 밀 수 있는 좁은 창은
 * {@code questionId} 를 함께 돌려주어 핸들러가 닫습니다.
 * {@link com.cuea.infrastructure.websocket.SessionSocketHandler} 가 같은 첫 질문
 * questionId 를 동일 {@link org.springframework.web.socket.WebSocketSession} 에 한 번만
 * 전송(claim-after-send)하므로, 두 경로가 겹쳐도 같은 세션 연결에 <b>중복 전송되지
 * 않습니다(same-session duplicate send 방지)</b>. 이는 Backend 의 {@code sendMessage}
 * 가 최대 1회라는 뜻이며, 프론트가 실제로 수신·처리했다는 ACK(end-to-end
 * exactly-once)는 아닙니다. 이 서비스는 "무엇을 복구할지" 만 정하고, "연결당 한 번만
 * 전송" 은 핸들러가 보장합니다.
 *
 * <h2>잘못된 replay 방지</h2>
 * <ul>
 *   <li>세션이 {@code IN_PROGRESS} 가 아니면(COMPLETED/ABORTED) 복구하지 않습니다.</li>
 *   <li>세션에 질문이 아직 없으면(폴링 미완료) 복구하지 않습니다. 폴링이 끝나면
 *       정상 push 가 나가고, 그 전에 끊겼다 다시 붙어도 다음 연결에서 복구됩니다.</li>
 *   <li>되묻기({@code REASK}) 는 복구 대상이 아닙니다(첫 주질문만).</li>
 * </ul>
 *
 * <p>이 서비스는 조회만 하며 세션 상태를 바꾸거나 AI 를 호출하지 않습니다. 복구 중
 * 발생한 예외는 연결 자체를 끊지 않도록 호출부(핸들러)가 흡수합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewFirstQuestionCatchUp {

    private final InterviewSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final QuestionPushFactory questionPushFactory;

    /**
     * 방금 연결된 소켓에 복구해 보내야 할 첫 질문을 돌려줍니다. 복구할 것이
     * 없으면({@code 아직 첫 질문 미생성 / 이미 답변 진행 / 세션 종료}) 빈 값입니다.
     * 부수효과 없이 조회만 하며, 실제 전송은 호출한 핸들러가 그 소켓에만 합니다.
     */
    @Transactional(readOnly = true)
    public Optional<FirstQuestion> firstQuestion(String interviewSessionId) {
        InterviewSession session = sessionRepository
                .findBySessionIdForInternal(interviewSessionId)
                .orElse(null);
        // 세션이 없거나 이미 종료(COMPLETED/ABORTED)면 복구하지 않습니다.
        if (session == null || !session.isInProgress()) {
            return Optional.empty();
        }

        List<Question> questions =
                questionRepository.findAllBySessionIdOrderByCreatedAtAsc(interviewSessionId);
        // 폴링이 아직 첫 질문을 저장하지 못했으면 복구할 게 없습니다. 폴링이 끝나면
        // 정상 push 가 나가므로 유실이 아닙니다.
        if (questions.isEmpty()) {
            return Optional.empty();
        }

        // 답변이 한 번이라도 진행됐으면(이후 질문이 생겼거나 첫 질문에 답변이 붙었으면)
        // 프론트는 이미 첫 질문을 받은 상태입니다. 과거 첫 질문을 다시 내려보내지 않습니다.
        if (answerAlreadyStarted(questions)) {
            return Optional.empty();
        }

        Question firstQuestion = questions.get(0);
        // 세션의 첫 결과가 주질문(questionNumber==1)이 아니면 복구 대상이 아닙니다.
        if (firstQuestion.getType() != QuestionType.QUESTION
                || firstQuestion.getQuestionNumber() != 1) {
            return Optional.empty();
        }

        QuestionPushMessage payload =
                questionPushFactory.create(firstQuestion, session.getQuestionCount());
        log.debug("연결 시점 첫 질문 복구 sessionId={} questionId={}",
                interviewSessionId, firstQuestion.getQuestionId());
        return Optional.of(new FirstQuestion(
                firstQuestion.getQuestionId(), QuestionPushMessage.of(payload)));
    }

    /**
     * 복구할 첫 질문 하나. {@code questionId} 는 핸들러가 소켓별 중복 전송을 막는 클레임
     * 키, {@code message} 는 프론트로 내려갈 질문 push 봉투입니다.
     */
    public record FirstQuestion(String questionId, SocketMessage<?> message) {
    }

    /**
     * 답변이 시작됐는지. 첫 질문 외에 다른 질문이 더 있거나, 첫 질문에 답변 오디오가
     * 붙어 있으면 사용자가 이미 진행 중이라고 봅니다. 둘 중 하나라도 참이면 복구하지
     * 않습니다.
     */
    private boolean answerAlreadyStarted(List<Question> questions) {
        if (questions.size() > 1) {
            return true;
        }
        return questions.get(0).getAnswerAudioObjectKey() != null;
    }
}
