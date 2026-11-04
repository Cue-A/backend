package com.cuea.domain.interview.dto.response;

import com.cuea.domain.interview.entity.BookmarkedQuestion;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "질문은행에 담긴 질문 한 건")
public record BookmarkResponse(

        @Schema(description = "메모 수정 · 삭제에 씁니다")
        String sessionId,

        @Schema(description = "메모 수정 · 삭제에 씁니다")
        String questionId,

        @Schema(description = "QUESTION | FOLLOWUP | REASK")
        QuestionType type,

        String questionText,

        @Schema(description = "AI 가 준 한글 카테고리 그대로. REASK 는 null")
        String category,

        @Schema(description = "L1 ~ L3. REASK 는 null")
        String difficulty,

        @Schema(description = "원래 질문의 문구. REASK 는 되물은 질문, FOLLOWUP 은 토픽의 주질문이고 QUESTION 은 null")
        String originalQuestionText,

        @Schema(description = "쓰지 않았으면 null")
        String memo,

        @Schema(description = "회사를 고르지 않은 면접이면 null")
        String companyName,

        String jobRole,

        @Schema(description = "담은 시각")
        OffsetDateTime bookmarkedAt
) {

    /** 질문 · 세션 · 회사는 목록 조회에서 함께 로드됩니다. */
    public static BookmarkResponse of(BookmarkedQuestion bookmark, String originalQuestionText) {
        Question question = bookmark.getQuestion();
        InterviewSession session = question.getSession();
        return new BookmarkResponse(
                question.getSessionId(),
                question.getQuestionId(),
                question.getType(),
                question.getText(),
                question.getCategory(),
                question.getDifficulty(),
                originalQuestionText,
                bookmark.getMemo(),
                session.getCompany() == null ? null : session.getCompany().getCompanyName(),
                session.getJobRole(),
                bookmark.getCreatedAt());
    }
}
