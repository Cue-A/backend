package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.infrastructure.ai.dto.AiReplayLogItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ReplayLogAssembler} 가 root 세션 질문 로그를 AI 재연습 계약의 replay_log 로
 * 바르게 조립하는지 검증합니다. REASK 제외·타입별 필드·정렬 안정성·필수 필드 검증을 봅니다.
 */
class ReplayLogAssemblerTest {

    private static final String ROOT = "sess_root";

    private QuestionRepository questionRepository;
    private ReplayLogAssembler assembler;

    @BeforeEach
    void setUp() {
        questionRepository = mock(QuestionRepository.class);
        assembler = new ReplayLogAssembler(questionRepository);
    }

    private Question q(String id, QuestionType type, String text, String category,
                       String difficulty, boolean spareTopic, OffsetDateTime createdAt) {
        return qn(id, type, text, category, difficulty, spareTopic, createdAt, 1);
    }

    private Question qn(String id, QuestionType type, String text, String category,
                        String difficulty, boolean spareTopic, OffsetDateTime createdAt,
                        int questionNumber) {
        return Question.builder()
                .sessionId(ROOT)
                .questionId(id)
                .type(type)
                .text(text)
                .category(category)
                .difficulty(difficulty)
                .spareTopic(spareTopic)
                .questionNumber(questionNumber)
                .topicIndex(0)
                .createdAt(createdAt)
                .build();
    }

    @Test
    void QUESTION_과_FOLLOWUP_을_계약_모양으로_변환하고_REASK_는_제외한다() {
        OffsetDateTime t = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(ROOT)).thenReturn(List.of(
                q("q_1", QuestionType.QUESTION, "지원 동기?", "지원동기", "L1", false, t),
                q("q_2", QuestionType.FOLLOWUP, "구체적으로?", null, "L2", false, t.plusSeconds(1)),
                q("q_2r", QuestionType.REASK, "다시 말씀해 주세요", null, null, false, t.plusSeconds(2)),
                q("q_3", QuestionType.QUESTION, "예비 토픽 주질문", "직무역량", "L1", true, t.plusSeconds(3))));

        List<AiReplayLogItem> log = assembler.assemble(ROOT);

        // REASK(q_2r) 제외. 나머지 3개.
        assertThat(log).hasSize(3);

        // 주질문: text/category/difficulty/is_spare_topic
        assertThat(log.get(0)).isEqualTo(
                AiReplayLogItem.question("지원 동기?", "지원동기", "L1", false));
        // 꼬리질문: difficulty 만, text/category/is_spare_topic 은 null
        assertThat(log.get(1).type()).isEqualTo("followup");
        assertThat(log.get(1).difficulty()).isEqualTo("L2");
        assertThat(log.get(1).text()).isNull();
        assertThat(log.get(1).category()).isNull();
        assertThat(log.get(1).isSpareTopic()).isNull();
        // 예비 토픽 주질문도 재사용되며 is_spare_topic=true 로 담긴다.
        assertThat(log.get(2)).isEqualTo(
                AiReplayLogItem.question("예비 토픽 주질문", "직무역량", "L1", true));
    }

    @Test
    void 타임스탬프가_같으면_questionId_문자열이_아니라_question_number_로_정렬한다() {
        OffsetDateTime same = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        // 회귀: created_at 동률 + questionId 문자열 정렬이면 "q_10" < "q_2" 로 10번이 먼저
        // 와 순서가 깨진다. question_number 로 정렬하면 2번이 먼저 와야 한다.
        // 저장 리스트 순서도 일부러 뒤집어 둔다.
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(ROOT)).thenReturn(List.of(
                qn("q_10", QuestionType.QUESTION, "열 번째", "직무역량", "L1", false, same, 10),
                qn("q_2", QuestionType.QUESTION, "두 번째", "지원동기", "L1", false, same, 2)));

        List<AiReplayLogItem> log = assembler.assemble(ROOT);

        assertThat(log).extracting(AiReplayLogItem::text)
                .containsExactly("두 번째", "열 번째");
    }

    @Test
    void 유효한_QUESTION_이_없으면_INTERNAL_ERROR_를_던진다() {
        OffsetDateTime t = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        // REASK 만 있는 경우.
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(ROOT)).thenReturn(List.of(
                q("q_1r", QuestionType.REASK, "다시", null, null, false, t)));

        assertThatThrownBy(() -> assembler.assemble(ROOT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 질문_로그가_비어_있으면_INTERNAL_ERROR_를_던진다() {
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(ROOT)).thenReturn(List.of());

        assertThatThrownBy(() -> assembler.assemble(ROOT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 주질문의_category_가_비어_있으면_INTERNAL_ERROR_로_거부한다() {
        OffsetDateTime t = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(ROOT)).thenReturn(List.of(
                q("q_1", QuestionType.QUESTION, "지원 동기?", null, "L1", false, t)));

        assertThatThrownBy(() -> assembler.assemble(ROOT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 꼬리질문의_difficulty_가_비어_있으면_INTERNAL_ERROR_로_거부한다() {
        OffsetDateTime t = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(ROOT)).thenReturn(List.of(
                q("q_1", QuestionType.QUESTION, "지원 동기?", "지원동기", "L1", false, t),
                q("q_2", QuestionType.FOLLOWUP, null, null, null, false, t.plusSeconds(1))));

        assertThatThrownBy(() -> assembler.assemble(ROOT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
