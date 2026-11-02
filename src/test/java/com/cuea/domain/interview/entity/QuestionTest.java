package com.cuea.domain.interview.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionTest {

    @Test
    void 되묻기의_원_질문은_reask_of_다() {
        Question reask = Question.builder().sessionId("sess_1").questionId("q_1r")
                .type(QuestionType.REASK).reaskOf("q_1").build();

        assertThat(reask.originalQuestionId()).isEqualTo("q_1");
    }

    @Test
    void 되묻기가_아니면_원_질문은_자기_자신이다() {
        Question question = Question.builder().sessionId("sess_1").questionId("q_2")
                .type(QuestionType.FOLLOWUP).build();

        assertThat(question.originalQuestionId()).isEqualTo("q_2");
    }
}
