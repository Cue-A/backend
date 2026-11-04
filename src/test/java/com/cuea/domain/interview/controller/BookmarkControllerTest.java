package com.cuea.domain.interview.controller;

import com.cuea.common.result.Result;
import com.cuea.domain.interview.dto.request.BookmarkToggleRequest;
import com.cuea.domain.interview.dto.response.BookmarkToggleResponse;
import com.cuea.domain.interview.service.BookmarkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 질문은행 컨트롤러가 경로 · 본문 값을 서비스에 그대로 넘기고 {@code Result<T>} 로 감싸는지 봅니다.
 * 인증은 {@code EndpointAuthGuardTest}, 규칙은 {@code BookmarkServiceTest} 몫입니다.
 */
class BookmarkControllerTest {

    private static final String USER_ID = "user-1";

    private BookmarkService bookmarkService;
    private BookmarkController controller;

    @BeforeEach
    void setUp() {
        bookmarkService = mock(BookmarkService.class);
        controller = new BookmarkController(bookmarkService);
    }

    @Test
    void 토글은_본문의_상태를_서비스에_넘긴다() {
        BookmarkToggleResponse dto = new BookmarkToggleResponse(false);
        when(bookmarkService.setBookmarked(USER_ID, "sess_1", "q_4r", false)).thenReturn(dto);

        Result<BookmarkToggleResponse> result =
                controller.setBookmarked(USER_ID, "sess_1", "q_4r", new BookmarkToggleRequest(false));

        verify(bookmarkService).setBookmarked(USER_ID, "sess_1", "q_4r", false);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isSameAs(dto);
    }
}
