package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionId;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 소유자가 있는 엔티티라 {@code findById} 가 없습니다. 소유자 검사는 세션을 거쳐야
 * 하므로({@code question} 에는 {@code user_id} 가 없음), 조회 서비스에서
 * {@code InterviewSessionRepository} 로 먼저 소유권을 확인하세요.
 */
public interface QuestionRepository extends Repository<Question, QuestionId> {

    List<Question> findAllBySessionId(String sessionId);

    Question save(Question question);
}
