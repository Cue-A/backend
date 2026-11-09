package com.cuea.domain.interview.controller;

import com.cuea.common.result.Result;
import com.cuea.domain.interview.dto.request.CommonQuestionFavoriteRequest;
import com.cuea.domain.interview.dto.request.CommonQuestionMemoRequest;
import com.cuea.domain.interview.dto.request.CommonQuestionRequest;
import com.cuea.domain.interview.dto.response.CommonQuestionFavoriteResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionListResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionMemoResponse;
import com.cuea.domain.interview.dto.response.CommonQuestionResponse;
import com.cuea.domain.interview.service.CommonQuestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공통 질문 컨트롤러가 경로 · 본문 값을 서비스에 그대로 넘기고 {@code Result<T>} 로 감싸는지 봅니다.
 * 인증은 {@code EndpointAuthGuardTest}, 규칙은 {@code CommonQuestionServiceTest} 몫입니다.
 */
class CommonQuestionControllerTest {

    private static final String USER_ID = "user-1";
    private static final String QUESTION_ID = "6f1c2a3b-0000-4000-8000-000000000001";
    private static final CommonQuestionResponse QUESTION = new CommonQuestionResponse(
            QUESTION_ID, "지원 동기를 말해 주세요.", "지원동기", false, null, null, true);

    private CommonQuestionService service;
    private CommonQuestionController controller;

    @BeforeEach
    void setUp() {
        service = mock(CommonQuestionService.class);
        controller = new CommonQuestionController(service);
    }

    @Test
    void 목록을_서비스에_넘긴다() {
        CommonQuestionListResponse dto = new CommonQuestionListResponse(List.of(QUESTION));
        when(service.list(USER_ID)).thenReturn(dto);

        Result<CommonQuestionListResponse> result = controller.list(USER_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isSameAs(dto);
    }

    @Test
    void 즐겨찾기는_본문의_상태를_서비스에_넘긴다() {
        CommonQuestionFavoriteResponse dto = new CommonQuestionFavoriteResponse(false);
        when(service.setFavorite(USER_ID, QUESTION_ID, false)).thenReturn(dto);

        Result<CommonQuestionFavoriteResponse> result =
                controller.setFavorite(USER_ID, QUESTION_ID, new CommonQuestionFavoriteRequest(false));

        assertThat(result.data()).isSameAs(dto);
    }

    @Test
    void 메모는_본문을_서비스에_넘긴다() {
        CommonQuestionMemoResponse dto = new CommonQuestionMemoResponse("두괄식으로");
        when(service.editMemo(USER_ID, QUESTION_ID, "두괄식으로")).thenReturn(dto);

        Result<CommonQuestionMemoResponse> result =
                controller.editMemo(USER_ID, QUESTION_ID, new CommonQuestionMemoRequest("두괄식으로"));

        assertThat(result.data()).isSameAs(dto);
    }

    @Test
    void 추가와_수정은_문구와_분류를_서비스에_넘긴다() {
        when(service.create(USER_ID, "지원 동기를 말해 주세요.", "지원동기")).thenReturn(QUESTION);
        when(service.update(USER_ID, QUESTION_ID, "지원 동기를 말해 주세요.", "지원동기")).thenReturn(QUESTION);
        CommonQuestionRequest request = new CommonQuestionRequest("지원 동기를 말해 주세요.", "지원동기");

        assertThat(controller.create(USER_ID, request).data()).isSameAs(QUESTION);
        assertThat(controller.update(USER_ID, QUESTION_ID, request).data()).isSameAs(QUESTION);
    }

    @Test
    void 삭제는_서비스에_넘기고_빈_성공_응답을_준다() {
        Result<Void> result = controller.delete(USER_ID, QUESTION_ID);

        verify(service).delete(USER_ID, QUESTION_ID);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isNull();
    }
}
