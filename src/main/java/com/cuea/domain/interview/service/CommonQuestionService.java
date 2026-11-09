package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.CommonQuestionFavoriteResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionListResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionMemoResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionResponse;
import com.cuea.domain.interview.entity.CommonQuestion;
import com.cuea.domain.interview.entity.CommonQuestionNote;
import com.cuea.domain.interview.repository.CommonQuestionNoteRepository;
import com.cuea.domain.interview.repository.CommonQuestionRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 질문은행 「공통 질문」 탭. 모두가 보는 기본 질문과 내가 추가한 내 질문을 함께 보여 주고, 질문마다 내 즐겨찾기 ·
 * 메모를 붙입니다.
 *
 * <p>질문 하나를 가리킬 때는 항상 "기본 질문이거나 내 질문" 범위에서 찾습니다. 남의 내 질문은 없는 질문과 똑같이
 * 404 입니다. 기본 질문은 즐겨찾기 · 메모만 되고 고치거나 지우려 하면 403 입니다. 기본 질문은 모두가 보므로 403 으로
 * 새는 정보가 없습니다.
 *
 * <p>내 질문 개수 상한은 폴더 상한처럼 여기서만 검사합니다. 같은 요청이 동시에 두 번 오면 둘 다 통과할 수 있습니다.
 */
@Service
@RequiredArgsConstructor
public class CommonQuestionService {

    static final int MAX_CUSTOM_QUESTIONS_PER_USER = 100;

    private final CommonQuestionRepository questionRepository;
    private final CommonQuestionNoteRepository noteRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** 내 질문(최근에 추가한 것부터) 다음에 기본 질문(정해진 순서). 즐겨찾기를 위로 올리는 정렬은 프론트가 합니다. */
    @Transactional(readOnly = true)
    public CommonQuestionListResponse list(String userId) {
        List<CommonQuestion> questions = Stream.concat(
                questionRepository.findAllByUser_UserIdOrderByCreatedAtDescCommonQuestionIdDesc(userId).stream(),
                questionRepository.findAllDefaults().stream()).toList();
        Map<Long, CommonQuestionNote> notes = noteRepository.findAllOwned(userId).stream()
                .collect(Collectors.toMap(note -> note.getCommonQuestion().getCommonQuestionId(), Function.identity()));
        return new CommonQuestionListResponse(questions.stream()
                .map(question -> CommonQuestionResponse.of(question, notes.get(question.getCommonQuestionId())))
                .toList());
    }

    /** 바뀔 상태를 받습니다. 이미 그 상태면 아무것도 바꾸지 않고 성공합니다. */
    @Transactional
    public CommonQuestionFavoriteResponse setFavorite(String userId, String questionId, boolean favorite) {
        noteOf(userId, getVisible(userId, questionId)).markFavorite(favorite, OffsetDateTime.now(clock));
        return new CommonQuestionFavoriteResponse(favorite);
    }

    /** 처음 쓰기와 고치기가 같습니다. 비었거나 공백뿐이면 메모를 지웁니다. 규칙은 질문은행 북마크 메모와 같습니다. */
    @Transactional
    public CommonQuestionMemoResponse editMemo(String userId, String questionId, String memo) {
        String normalized = BookmarkMemo.normalize(memo);
        noteOf(userId, getVisible(userId, questionId)).editMemo(normalized);
        return new CommonQuestionMemoResponse(normalized);
    }

    @Transactional
    public CommonQuestionResponse create(String userId, String text, String category) {
        String normalizedText = CustomQuestionRule.text(text);
        String normalizedCategory = CustomQuestionRule.category(category);
        if (questionRepository.countByUser_UserId(userId) >= MAX_CUSTOM_QUESTIONS_PER_USER) {
            throw new BusinessException(ErrorCode.CUSTOM_QUESTION_LIMIT_EXCEEDED);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        CommonQuestion question = questionRepository.save(CommonQuestion.builder()
                .publicId(UUID.randomUUID())
                .user(user)
                .text(normalizedText)
                .category(normalizedCategory)
                .build());
        return CommonQuestionResponse.of(question, null);
    }

    /** 즐겨찾기 · 메모는 그대로 둡니다. */
    @Transactional
    public CommonQuestionResponse update(String userId, String questionId, String text, String category) {
        String normalizedText = CustomQuestionRule.text(text);
        String normalizedCategory = CustomQuestionRule.category(category);
        CommonQuestion question = getOwnCustom(userId, questionId);
        question.edit(normalizedText, normalizedCategory);
        return CommonQuestionResponse.of(question,
                noteRepository.findOwned(userId, question.getCommonQuestionId()).orElse(null));
    }

    /** 즐겨찾기 · 메모도 함께 지워집니다(DB cascade). */
    @Transactional
    public void delete(String userId, String questionId) {
        questionRepository.delete(getOwnCustom(userId, questionId));
    }

    /** 노트가 없으면 빈 노트를 만들어 돌려줍니다. 처음 요청이 동시에 와도 행은 하나입니다. */
    private CommonQuestionNote noteOf(String userId, CommonQuestion question) {
        noteRepository.insertIfAbsent(userId, question.getCommonQuestionId());
        return noteRepository.findOwned(userId, question.getCommonQuestionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
    }

    /** 보이는 질문 중 내 질문만. 보이는데 내 질문이 아니면 기본 질문입니다. */
    private CommonQuestion getOwnCustom(String userId, String questionId) {
        CommonQuestion question = getVisible(userId, questionId);
        if (!question.isCustom()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "기본 질문은 고치거나 지울 수 없습니다");
        }
        return question;
    }

    /** 없는 질문 · 남의 내 질문 · UUID 가 아닌 값은 구별하지 않고 404 입니다. */
    private CommonQuestion getVisible(String userId, String questionId) {
        return questionRepository.findVisible(parsePublicId(questionId), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
    }

    private static UUID parsePublicId(String questionId) {
        try {
            return UUID.fromString(questionId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
        }
    }
}
