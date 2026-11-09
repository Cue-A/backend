package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.dto.response.CommonQuestionListResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionResponse;
import com.cuea.domain.interview.repository.CommonQuestionNoteRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공통 질문을 실제 PostgreSQL 에서 끝까지 돌려 봅니다. 노트가 한 행으로 모이는지, 지우면 cascade 로 사라지는지,
 * 응답 JSON 키가 프론트 타입(frontend {@code src/domain/question/api/questionResponse.ts})과 같은지 봅니다.
 */
@Import({CommonQuestionService.class, CommonQuestionServiceIntegrationTest.FixedClock.class})
class CommonQuestionServiceIntegrationTest extends PostgresRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");

    /** 프론트 {@code CommonQuestionResponse} 의 키. 하나라도 다르면 화면이 값을 못 읽습니다. */
    private static final List<String> FRONT_QUESTION_KEYS =
            List.of("questionId", "text", "category", "favorite", "favoritedAt", "memo", "custom");

    /** Spring 의 ObjectMapper 와 같이 java.time 을 문자열로 쓰고 null 도 키를 남깁니다(application.yml). */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .build();

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private CommonQuestionService service;

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

    @Test
    void 추가한_내_질문이_맨_위에_오고_즐겨찾기_메모가_붙어_나온다() {
        CommonQuestionResponse created = service.create(user.getUserId(), " 지원 동기를 말해 주세요. ", "지원동기");
        service.setFavorite(user.getUserId(), created.questionId(), true);
        service.editMemo(user.getUserId(), created.questionId(), "회사 이야기부터");
        flushAndClear();

        CommonQuestionListResponse list = service.list(user.getUserId());

        assertThat(list.questions()).hasSize(13);
        CommonQuestionResponse mine = list.questions().getFirst();
        assertThat(mine.questionId()).isEqualTo(created.questionId());
        assertThat(mine.text()).isEqualTo("지원 동기를 말해 주세요.");
        assertThat(mine.custom()).isTrue();
        assertThat(mine.favorite()).isTrue();
        assertThat(mine.favoritedAt()).isNotNull();
        assertThat(mine.favoritedAt().toInstant()).isEqualTo(NOW);
        assertThat(mine.memo()).isEqualTo("회사 이야기부터");
        assertThat(list.questions().subList(1, 13)).noneMatch(CommonQuestionResponse::custom)
                .noneMatch(CommonQuestionResponse::favorite);
        assertThat(list.questions().get(1).text()).isEqualTo("1분 자기소개를 해주세요.");
    }

    @Test
    void 응답_JSON_키가_프론트_타입과_같다() {
        CommonQuestionResponse created = service.create(user.getUserId(), "지원 동기를 말해 주세요.", "지원동기");

        JsonNode list = objectMapper.valueToTree(service.list(user.getUserId()));
        assertThat(fieldNames(list)).containsExactly("questions");
        for (JsonNode question : list.path("questions")) {
            assertThat(fieldNames(question)).containsExactlyInAnyOrderElementsOf(FRONT_QUESTION_KEYS);
        }
        assertThat(list.path("questions").get(0).path("favoritedAt").isNull()).isTrue();
        assertThat(list.path("questions").get(0).path("memo").isNull()).isTrue();

        assertThat(fieldNames(objectMapper.valueToTree(created))).containsExactlyInAnyOrderElementsOf(FRONT_QUESTION_KEYS);
        assertThat(fieldNames(objectMapper.valueToTree(service.setFavorite(user.getUserId(), created.questionId(), true))))
                .containsExactly("favorite");
        assertThat(fieldNames(objectMapper.valueToTree(service.editMemo(user.getUserId(), created.questionId(), "메모"))))
                .containsExactly("memo");

        flushAndClear();
        JsonNode favorited = objectMapper.valueToTree(service.list(user.getUserId())).path("questions").get(0);
        assertThat(favorited.path("favoritedAt").isTextual()).isTrue();
    }

    @Test
    void 기본_질문의_즐겨찾기와_메모는_한_노트_행에_모이고_남에게는_보이지_않는다() {
        String intro = defaultQuestionId();

        service.setFavorite(user.getUserId(), intro, true);
        service.setFavorite(user.getUserId(), intro, true);
        service.editMemo(user.getUserId(), intro, "30초 안에");
        service.setFavorite(user.getUserId(), intro, false);
        flushAndClear();

        assertThat(noteRepository.findAllOwned(user.getUserId())).hasSize(1);
        CommonQuestionResponse mine = service.list(user.getUserId()).questions().getFirst();
        assertThat(mine.favorite()).isFalse();
        assertThat(mine.favoritedAt()).isNull();
        assertThat(mine.memo()).isEqualTo("30초 안에");

        CommonQuestionResponse others = service.list(other.getUserId()).questions().getFirst();
        assertThat(others.questionId()).isEqualTo(intro);
        assertThat(others.memo()).isNull();
    }

    @Test
    void 내_질문을_고치면_즐겨찾기_메모가_그대로_남는다() {
        String id = service.create(user.getUserId(), "고치기 전", "기타").questionId();
        service.setFavorite(user.getUserId(), id, true);
        service.editMemo(user.getUserId(), id, "메모");
        flushAndClear();

        CommonQuestionResponse updated = service.update(user.getUserId(), id, "고친 뒤", "직무");
        flushAndClear();

        assertThat(updated.text()).isEqualTo("고친 뒤");
        assertThat(updated.favorite()).isTrue();
        assertThat(updated.memo()).isEqualTo("메모");
        assertThat(service.list(user.getUserId()).questions().getFirst())
                .extracting(CommonQuestionResponse::text, CommonQuestionResponse::category)
                .containsExactly("고친 뒤", "직무");
    }

    @Test
    void 내_질문을_지우면_노트도_사라지고_목록에서_빠진다() {
        String id = service.create(user.getUserId(), "지울 질문", "기타").questionId();
        service.setFavorite(user.getUserId(), id, true);
        service.editMemo(user.getUserId(), id, "메모");
        flushAndClear();

        service.delete(user.getUserId(), id);
        flushAndClear();

        assertThat(noteRepository.findAllOwned(user.getUserId())).isEmpty();
        assertThat(service.list(user.getUserId()).questions()).hasSize(12).noneMatch(q -> q.questionId().equals(id));
        assertErrorCode(() -> service.delete(user.getUserId(), id), ErrorCode.QUESTION_NOT_FOUND);
    }

    @Test
    void 남의_내_질문은_목록에_없고_즐겨찾기_메모_수정_삭제_모두_404_이다() {
        String others = service.create(other.getUserId(), "남의 질문", "기타").questionId();
        flushAndClear();

        assertThat(service.list(user.getUserId()).questions()).noneMatch(q -> q.questionId().equals(others));
        assertErrorCode(() -> service.setFavorite(user.getUserId(), others, true), ErrorCode.QUESTION_NOT_FOUND);
        assertErrorCode(() -> service.editMemo(user.getUserId(), others, "메모"), ErrorCode.QUESTION_NOT_FOUND);
        assertErrorCode(() -> service.update(user.getUserId(), others, "문구", "기타"), ErrorCode.QUESTION_NOT_FOUND);
        assertErrorCode(() -> service.delete(user.getUserId(), others), ErrorCode.QUESTION_NOT_FOUND);
    }

    @Test
    void 기본_질문은_고치거나_지울_수_없다() {
        String intro = defaultQuestionId();

        assertErrorCode(() -> service.update(user.getUserId(), intro, "문구", "기타"), ErrorCode.FORBIDDEN);
        assertErrorCode(() -> service.delete(user.getUserId(), intro), ErrorCode.FORBIDDEN);
        flushAndClear();

        assertThat(service.list(user.getUserId()).questions().getFirst().text()).isEqualTo("1분 자기소개를 해주세요.");
    }

    @Test
    void 내_질문은_100개까지이고_남의_개수와_섞이지_않는다() {
        service.create(other.getUserId(), "남의 질문", "기타");
        IntStream.rangeClosed(1, 100).forEach(i -> service.create(user.getUserId(), "질문 " + i, "기타"));

        assertErrorCode(() -> service.create(user.getUserId(), "101번째", "기타"),
                ErrorCode.CUSTOM_QUESTION_LIMIT_EXCEEDED);
        assertThat(service.list(user.getUserId()).questions()).hasSize(112);
    }

    private String defaultQuestionId() {
        return service.list(user.getUserId()).questions().getFirst().questionId();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
