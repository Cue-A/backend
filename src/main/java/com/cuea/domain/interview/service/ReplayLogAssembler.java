package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.entity.Question;
import com.cuea.domain.interview.entity.QuestionType;
import com.cuea.domain.interview.repository.QuestionRepository;
import com.cuea.infrastructure.ai.dto.AiReplayLogItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 최초(root) 세션의 질문 로그를 AI 재연습 계약의 {@code replay_log} 배열로 조립합니다.
 *
 * <p>진실 소스는 Backend DB 입니다. AI 는 1회차 로그를 영속 저장하지 않으므로,
 * 재면접 때 Spring 이 root 세션의 질문 로그를 복원해 함께 보냅니다. docs/12-replay.md 참고.
 *
 * <h3>조립 규칙</h3>
 * <ul>
 *   <li>정렬: {@code created_at} 오름차순. 같은 타임스탬프에서는 {@code question_number} 로
 *       2차 정렬합니다. {@code question_number} 는 되묻기({@code REASK})에서 올라가지 않지만,
 *       REASK 는 아래에서 제외되고 남는 QUESTION/FOLLOWUP 의 번호는 단조 증가하므로 보존
 *       항목의 순서가 흔들리지 않습니다. (questionId 문자열은 {@code "q_10" < "q_2"} 처럼
 *       숫자 순서를 깨뜨리므로 정렬 키로 쓰지 않습니다.)</li>
 *   <li>{@code REASK} 제외.</li>
 *   <li>{@code QUESTION} → text/category/difficulty/is_spare_topic 포함.</li>
 *   <li>{@code FOLLOWUP} → difficulty 만 포함.</li>
 * </ul>
 *
 * <h3>검증</h3>
 * <p>비어 있거나 {@code REASK} 만 있어 유효한 {@code QUESTION} 이 하나도 없으면 재면접을
 * 성립시킬 수 없으므로 일반 면접으로 조용히 전환하지 않고 오류를 냅니다. 또한 필수 필드가
 * 누락된 질문 로그로 잘못된 {@code replay_log} 를 AI 에 보내지 않도록 변환 시 필드 존재를
 * 확인합니다. 이 둘은 요청이 아니라 <b>저장된 데이터의 무결성 문제</b>라 {@code INTERNAL_ERROR}
 * (500)로 처리하고, 구체적 원인(필드명·questionId)은 서버 로그로만 남기며 사용자에게는 일반
 * 메시지를 돌려줍니다.
 *
 * <p><b>'질문 2개 이상' 같은 하한은 확정된 AI 계약에 없으므로 임의로 두지 않습니다.</b>
 * 유효한 {@code QUESTION} 이 최소 하나만 있으면 조립합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplayLogAssembler {

    private final QuestionRepository questionRepository;

    /**
     * root 세션의 질문 로그를 {@code replay_log} 로 조립합니다.
     *
     * @param rootSessionId 회차 묶음의 최초 세션 ID
     * @return REASK 를 뺀 질문 로그를 AI 계약 모양으로 변환한 배열. 비지 않음
     * @throws BusinessException 유효한 QUESTION 이 없거나 필수 필드가 누락된 경우. 저장된
     *                           데이터의 무결성 문제이므로 {@code INTERNAL_ERROR}(500)이며,
     *                           상세 원인은 서버 로그로만 남깁니다.
     */
    @Transactional(readOnly = true)
    public List<AiReplayLogItem> assemble(String rootSessionId) {
        List<Question> ordered = questionRepository
                .findAllBySessionIdOrderByCreatedAtAsc(rootSessionId)
                .stream()
                // 1차: created_at(AI 계약 정렬 기준). 2차: question_number.
                // created_at 이 드물게 동률일 때 questionId 문자열("q_2" vs "q_10")로 정렬하면
                // 숫자 순서가 깨지므로, AI 가 매긴 출제 순번(question_number)을 2차 키로 씁니다.
                // REASK 는 번호가 올라가지 않지만 아래에서 제외되므로 보존 항목 순서에 영향이
                // 없고, 보존되는 QUESTION/FOLLOWUP 의 question_number 는 단조 증가합니다.
                .sorted(Comparator
                        .comparing(Question::getCreatedAt)
                        .thenComparingInt(Question::getQuestionNumber))
                .toList();

        List<AiReplayLogItem> items = new ArrayList<>(ordered.size());
        boolean hasValidQuestion = false;

        for (Question question : ordered) {
            if (question.getType() == QuestionType.REASK) {
                continue;
            }
            if (question.getType() == QuestionType.QUESTION) {
                items.add(toQuestionItem(rootSessionId, question));
                hasValidQuestion = true;
            } else if (question.getType() == QuestionType.FOLLOWUP) {
                items.add(toFollowupItem(rootSessionId, question));
            }
        }

        if (!hasValidQuestion) {
            // 저장된 root 로그가 비었거나 REASK 만 있는 데이터 무결성 문제입니다. 사용자에게는
            // 서버 오류로 보이고, 원인은 세션 ID 로만 로그에 남깁니다(민감 데이터 없음).
            log.warn("재면접 replay_log 조립 실패: 유효한 QUESTION 이 없습니다 rootSessionId={}", rootSessionId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return items;
    }

    /**
     * 주질문 변환. text·category·difficulty 가 비어 있으면 잘못된 replay_log 이므로
     * AI 에 보내기 전에 거부합니다(AI 가 INVALID_CATEGORY 등으로 되돌려주기 전에 차단).
     */
    private AiReplayLogItem toQuestionItem(String rootSessionId, Question question) {
        requireField(rootSessionId, question.getText(), "text", question);
        requireField(rootSessionId, question.getCategory(), "category", question);
        requireField(rootSessionId, question.getDifficulty(), "difficulty", question);
        return AiReplayLogItem.question(
                question.getText(),
                question.getCategory(),
                question.getDifficulty(),
                question.isSpareTopic());
    }

    /** 꼬리질문 변환. difficulty 만 담되, 누락이면 거부합니다. */
    private AiReplayLogItem toFollowupItem(String rootSessionId, Question question) {
        requireField(rootSessionId, question.getDifficulty(), "difficulty", question);
        return AiReplayLogItem.followup(question.getDifficulty());
    }

    /**
     * 필수 필드 누락은 저장 데이터 무결성 문제입니다. 사용자에게는 {@code INTERNAL_ERROR}
     * 일반 메시지만 돌려주고, 어떤 필드가 어느 질문에서 비었는지는 서버 로그로만 남깁니다.
     * 로그에는 식별자(fieldName·questionId·rootSessionId)만 적고 이력서·답변 등 민감
     * 데이터는 남기지 않습니다.
     */
    private void requireField(String rootSessionId, String value, String fieldName, Question question) {
        if (value == null || value.isBlank()) {
            log.warn("재면접 replay_log 조립 실패: 필수 필드 누락 field={} questionId={} rootSessionId={}",
                    fieldName, question.getQuestionId(), rootSessionId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
