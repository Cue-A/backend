package com.cuea.domain.report.dto.response;

import com.cuea.domain.interview.entity.Question;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 리포트 응답을 만들 때 AI 결과의 질문 ID 로 우리 질문을 찾습니다. 되묻기 ID 는 원 질문으로 잇습니다. */
public final class ReportQuestions {

    private final Map<String, Question> byId;

    private ReportQuestions(Map<String, Question> byId) {
        this.byId = byId;
    }

    /** (session_id, question_id) 가 PK 라 한 세션 안에서 ID 가 겹치지 않습니다. */
    public static ReportQuestions of(List<Question> questions) {
        return new ReportQuestions(questions.stream()
                .collect(Collectors.toMap(Question::getQuestionId, Function.identity())));
    }

    /** 원 질문 ID. 모르는 ID 면 그대로 돌려줍니다. */
    public String originalIdOf(String questionId) {
        Question question = byId.get(questionId);
        return question == null ? questionId : question.originalQuestionId();
    }

    public boolean isReask(String questionId) {
        return !Objects.equals(originalIdOf(questionId), questionId);
    }

    /** 모르는 ID 면 null. */
    public String textOf(String questionId) {
        Question question = byId.get(questionId);
        return question == null ? null : question.getText();
    }

    /** 모르는 ID 면 null. */
    public Integer numberOf(String questionId) {
        Question question = byId.get(questionId);
        return question == null ? null : question.getQuestionNumber();
    }
}
