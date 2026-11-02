package com.cuea.domain.report.dto.response;

import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportQuestionsTest {

    private final ReportQuestions questions = ReportQuestions.of(List.of(
            question("q_1", QuestionType.QUESTION, 1, null, "지원 동기를 말씀해 주세요"),
            question("q_1r", QuestionType.REASK, 1, "q_1", "조금 더 구체적으로 말씀해 주시겠어요?")));

    @Test
    void 되묻기_ID_는_원_질문으로_잇는다() {
        assertThat(questions.originalIdOf("q_1r")).isEqualTo("q_1");
        assertThat(questions.isReask("q_1r")).isTrue();
    }

    @Test
    void 일반_질문은_자기_자신이다() {
        assertThat(questions.originalIdOf("q_1")).isEqualTo("q_1");
        assertThat(questions.isReask("q_1")).isFalse();
    }

    /** AI 결과에만 있는 ID 라도 응답이 깨지지 않게 그대로 둡니다. */
    @Test
    void 모르는_ID_는_그대로_두고_원문과_번호는_null() {
        assertThat(questions.originalIdOf("q_9")).isEqualTo("q_9");
        assertThat(questions.isReask("q_9")).isFalse();
        assertThat(questions.textOf("q_9")).isNull();
        assertThat(questions.numberOf("q_9")).isNull();
    }

    @Test
    void ID_가_null_이어도_터지지_않는다() {
        assertThat(questions.originalIdOf(null)).isNull();
        assertThat(questions.isReask(null)).isFalse();
        assertThat(questions.textOf(null)).isNull();
    }

    @Test
    void 원문과_번호를_찾는다() {
        assertThat(questions.textOf("q_1")).isEqualTo("지원 동기를 말씀해 주세요");
        assertThat(questions.numberOf("q_1r")).isEqualTo(1);
    }

    private static Question question(String id, QuestionType type, int number, String reaskOf, String text) {
        return Question.builder().sessionId("sess_1").questionId(id).type(type)
                .questionNumber(number).reaskOf(reaskOf).text(text).build();
    }
}
