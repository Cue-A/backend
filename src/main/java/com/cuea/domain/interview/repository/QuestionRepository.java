package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionId;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 소유자가 있는 엔티티라 {@code findById} 가 없습니다. 소유자 검사는 세션을 거쳐야
 * 하므로({@code question} 에는 {@code user_id} 가 없음), 조회 서비스에서
 * {@code InterviewSessionRepository} 로 먼저 소유권을 확인하세요.
 */
public interface QuestionRepository extends Repository<Question, QuestionId> {

    List<Question> findAllBySessionId(String sessionId);

    /**
     * 세션의 질문을 나간 순서대로. 되묻기는 원 질문 뒤에 옵니다.
     * {@code question_number} 는 되묻기에서 올라가지 않아 순서 기준으로 쓸 수 없습니다.
     */
    List<Question> findAllBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * 세션 스코프 복합키로 질문 하나를 찾습니다. 답변 제출 시 대상 질문에 answer
     * object key 를 붙이기 위해 씁니다. 소유권은 세션으로 먼저 확인한 뒤 호출하세요.
     */
    Optional<Question> findBySessionIdAndQuestionId(String sessionId, String questionId);

    Question save(Question question);
}
