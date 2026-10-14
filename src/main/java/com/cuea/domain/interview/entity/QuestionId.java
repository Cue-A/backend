package com.cuea.domain.interview.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link Question} 의 복합 PK. {@code (session_id, question_id)} 입니다.
 *
 * <p>AI 가 주는 {@code question_id} 는 {@code "q_1"}, {@code "q_4r"} 처럼
 * <b>세션 안에서만 유일합니다.</b> 단독 PK 로 쓰면 다른 세션의 {@code "q_1"} 과
 * 충돌합니다. docs/02-database.md 의 복합 PK 절 참고.
 */
public class QuestionId implements Serializable {

    private String sessionId;
    private String questionId;

    protected QuestionId() {
    }

    public QuestionId(String sessionId, String questionId) {
        this.sessionId = sessionId;
        this.questionId = questionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QuestionId other)) {
            return false;
        }
        return Objects.equals(sessionId, other.sessionId)
                && Objects.equals(questionId, other.questionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, questionId);
    }

    @Override
    public String toString() {
        return sessionId + "/" + questionId;
    }
}
