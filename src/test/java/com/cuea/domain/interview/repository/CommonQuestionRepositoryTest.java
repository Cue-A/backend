package com.cuea.domain.interview.repository;

import com.cuea.domain.interview.entity.CommonQuestion;
import com.cuea.domain.interview.entity.CommonQuestionNote;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * V5 시드와 공통 질문 · 노트의 소유자 스코프, DB 제약(분류 check · UNIQUE · cascade)을 실제 PostgreSQL 로 봅니다.
 *
 * <p>노트 삽입은 네이티브 쿼리라 영속성 컨텍스트를 거치지 않으므로, 확인 전에 {@code em.clear()} 로 1차 캐시를 비웁니다.
 */
class CommonQuestionRepositoryTest extends PostgresRepositoryTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-01T00:00:00Z");

    @Autowired
    private CommonQuestionRepository questionRepository;

    @Autowired
    private CommonQuestionNoteRepository noteRepository;

    @Autowired
    private TestEntityManager em;

    private User user;
    private User other;

    @BeforeEach
    void setUp() {
        user = em.persist(User.create(null, "면접자"));
        other = em.persist(User.create(null, "남"));
        em.flush();
    }

    /** 프론트 목업(questionMock.ts)과 문구 · 분류 · 순서가 하나라도 다르면 화면이 목업과 달라집니다. */
    @Test
    void 기본_질문_12개가_프론트_목업과_같은_문구_분류_순서로_들어_있다() {
        List<CommonQuestion> defaults = questionRepository.findAllDefaults();

        assertThat(defaults).extracting(CommonQuestion::getText, CommonQuestion::getCategory).containsExactly(
                tuple("1분 자기소개를 해주세요.", "인성"),
                tuple("본인의 장점과 보완할 노력을 말해주세요.", "인성"),
                tuple("협업 중 갈등을 해결한 경험을 말해주세요.", "경험"),
                tuple("가장 도전적이었던 프로젝트와 그 결과를 설명해 주세요.", "경험"),
                tuple("실패했던 경험과 그 경험에서 배운 점을 말해주세요.", "경험"),
                tuple("팀에서 본인이 주로 맡는 역할은 무엇인가요?", "인성"),
                tuple("스트레스를 받을 때 어떻게 해소하나요?", "인성"),
                tuple("마감이 촉박한 상황에서 우선순위를 어떻게 정하나요?", "경험"),
                tuple("리더로서 팀을 이끌었던 경험이 있나요?", "경험"),
                tuple("상사와 의견이 달랐을 때 어떻게 대처했나요?", "인성"),
                tuple("입사 후 3년 동안의 성장 계획을 말해주세요.", "지원동기"),
                tuple("새로운 기술을 빠르게 익혔던 경험을 말해주세요.", "경험"));
        assertThat(defaults).noneMatch(CommonQuestion::isCustom);
        assertThat(defaults).extracting(CommonQuestion::getPublicId).doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    void 내_질문을_추가해도_기본_질문_목록에는_섞이지_않는다() {
        custom(user, "내 질문", T0);
        em.flush();

        assertThat(questionRepository.findAllDefaults()).hasSize(12).noneMatch(CommonQuestion::isCustom);
    }

    @Test
    void 기본_질문과_내_질문은_보이고_남의_내_질문은_보이지_않는다() {
        CommonQuestion mine = custom(user, "내 질문", T0);
        CommonQuestion others = custom(other, "남의 질문", T0);
        UUID defaultId = questionRepository.findAllDefaults().getFirst().getPublicId();
        em.flush();
        em.clear();

        assertThat(questionRepository.findVisible(defaultId, user.getUserId())).isPresent();
        assertThat(questionRepository.findVisible(mine.getPublicId(), user.getUserId())).isPresent();
        assertThat(questionRepository.findVisible(others.getPublicId(), user.getUserId())).isEmpty();
        assertThat(questionRepository.findVisible(UUID.randomUUID(), user.getUserId())).isEmpty();
    }

    @Test
    void 내_질문은_최근에_추가한_것부터_나오고_남의_것은_빠진다() {
        custom(user, "먼저", T0);
        custom(user, "나중", T0.plusMinutes(1));
        custom(other, "남의 질문", T0.plusMinutes(2));
        em.flush();
        em.clear();

        assertThat(questionRepository.findAllByUser_UserIdOrderByCreatedAtDescCommonQuestionIdDesc(user.getUserId()))
                .extracting(CommonQuestion::getText).containsExactly("나중", "먼저");
        assertThat(questionRepository.countByUser_UserId(user.getUserId())).isEqualTo(2);
    }

    @Test
    void 분류는_다섯_가지만_DB_가_받는다() {
        for (String category : List.of("인성", "경험", "지원동기", "직무", "기타")) {
            em.persist(question(user, "분류 " + category, category, T0));
        }
        em.flush();

        assertThatThrownBy(() -> {
            em.persist(question(user, "AI 분류", "직무역량", T0));
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void 노트_빈_행은_두_번_만들어도_하나만_남는다() {
        Long questionId = questionRepository.findAllDefaults().getFirst().getCommonQuestionId();

        assertThat(noteRepository.insertIfAbsent(user.getUserId(), questionId)).isEqualTo(1);
        assertThat(noteRepository.insertIfAbsent(user.getUserId(), questionId)).isZero();
        em.clear();

        CommonQuestionNote note = noteRepository.findOwned(user.getUserId(), questionId).orElseThrow();
        assertThat(note.isFavorite()).isFalse();
        assertThat(note.getFavoritedAt()).isNull();
        assertThat(note.getMemo()).isNull();
        assertThat(note.getCreatedAt()).isNotNull();
        assertThat(note.getUpdatedAt()).isNotNull();
    }

    @Test
    void 노트는_주인_것만_조회된다() {
        Long questionId = questionRepository.findAllDefaults().getFirst().getCommonQuestionId();
        noteRepository.insertIfAbsent(user.getUserId(), questionId);
        em.clear();

        assertThat(noteRepository.findOwned(other.getUserId(), questionId)).isEmpty();
        assertThat(noteRepository.findAllOwned(other.getUserId())).isEmpty();
        assertThat(noteRepository.findAllOwned(user.getUserId())).hasSize(1);
    }

    @Test
    void 내_질문을_지우면_그_질문의_노트도_지워진다() {
        CommonQuestion mine = custom(user, "지울 질문", T0);
        em.flush();
        noteRepository.insertIfAbsent(user.getUserId(), mine.getCommonQuestionId());
        em.clear();

        questionRepository.delete(questionRepository.findVisible(mine.getPublicId(), user.getUserId()).orElseThrow());
        em.flush();
        em.clear();

        assertThat(noteRepository.findAllOwned(user.getUserId())).isEmpty();
        assertThat(questionRepository.findVisible(mine.getPublicId(), user.getUserId())).isEmpty();
    }

    private CommonQuestion custom(User owner, String text, OffsetDateTime createdAt) {
        return em.persist(question(owner, text, "기타", createdAt));
    }

    private static CommonQuestion question(User owner, String text, String category, OffsetDateTime createdAt) {
        return CommonQuestion.builder()
                .publicId(UUID.randomUUID()).user(owner).text(text).category(category).createdAt(createdAt)
                .build();
    }
}
