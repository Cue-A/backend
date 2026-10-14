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
 * WebSocket 연결 직후, 아직 전달됐어야 할 첫 질문을 그 연결에만 복구합니다.
 *
 * <p>복구 대상은 <b>세션의 첫 주질문 하나</b>이며, <b>아직 답변이 시작되지 않았을
 * 때만</b> 돌려줍니다. 이후 질문이 생겼거나 첫 질문에 답변이 붙었다면 프론트는 이미
 * 첫 질문을 받은 것이므로, 과거 첫 질문을 다시 보내면 안 됩니다.
 *
 * <p>질문 목록이 비어 있는 것은 유실이 아니라 아직 폴링이 첫 질문을 저장하지 않은
 * 정상 상태일 수 있습니다(그 경우 뒤이어 정상 push 가 나갑니다).
 *
 * <p>조회만 하며, 전송·중복 방지는 호출부(핸들러)가 담당합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewFirstQuestionCatchUp {

    private final InterviewSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final QuestionPushFactory questionPushFactory;

    @Transactional(readOnly = true)
    public Optional<FirstQuestion> firstQuestion(String interviewSessionId) {
        InterviewSession session = sessionRepository
                .findBySessionIdForInternal(interviewSessionId)
                .orElse(null);
        if (session == null || !session.isInProgress()) {
            return Optional.empty();
        }

        List<Question> questions =
                questionRepository.findAllBySessionIdOrderByCreatedAtAsc(interviewSessionId);
        // 빈 목록은 유실이 아니라 폴링이 아직 첫 질문을 저장하지 않은 정상 상태다.
        if (questions.isEmpty()) {
            return Optional.empty();
        }

        // 답변이 시작됐으면(이후 질문 존재 or 첫 질문에 답변) 프론트는 이미 받은 것이므로
        // 과거 첫 질문을 다시 보내지 않는다.
        if (answerAlreadyStarted(questions)) {
            return Optional.empty();
        }

        Question firstQuestion = questions.get(0);
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

    /** {@code questionId} 는 핸들러가 소켓별 중복 전송을 막는 클레임 키입니다. */
    public record FirstQuestion(String questionId, SocketMessage<?> message) {
    }

    /** 이후 질문이 더 있거나 첫 질문에 답변 오디오가 붙었으면 답변이 시작된 것으로 봅니다. */
    private boolean answerAlreadyStarted(List<Question> questions) {
        if (questions.size() > 1) {
            return true;
        }
        return questions.get(0).getAnswerAudioObjectKey() != null;
    }
}
