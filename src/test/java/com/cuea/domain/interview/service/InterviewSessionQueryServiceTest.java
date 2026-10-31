package com.cuea.domain.interview.service;

import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.repository.InterviewSessionRepository;
import com.cuea.domain.interview.repository.QuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewSessionQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-07-01T00:00:00Z");

    private InterviewSessionRepository sessionRepository;
    private InterviewSessionQueryService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        service = new InterviewSessionQueryService(sessionRepository, mock(QuestionRepository.class));
    }

    @Test
    void 일회차는_1_재연습은_먼저_시작한_재연습_수_더하기_2() {
        InterviewSession root = session("root", null, 0);
        InterviewSession second = session("second", root, 1);
        InterviewSession third = session("third", root, 2);
        when(sessionRepository.findRetriesOf(USER_ID, List.of("root"), SessionStatus.ABORTED))
                .thenReturn(List.of(second, third));

        Map<String, Integer> rounds = service.roundsOf(USER_ID, List.of(third, second, root));

        assertThat(rounds).containsEntry("root", 1).containsEntry("second", 2).containsEntry("third", 3);
    }

    /** 중단된 재연습은 조회에서 빠지므로 그 뒤 회차가 당겨집니다. */
    @Test
    void 중단된_재연습은_세지_않는다() {
        InterviewSession root = session("root", null, 0);
        InterviewSession afterAborted = session("after", root, 3);
        when(sessionRepository.findRetriesOf(USER_ID, List.of("root"), SessionStatus.ABORTED))
                .thenReturn(List.of(session("second", root, 1), afterAborted));

        assertThat(service.roundsOf(USER_ID, List.of(afterAborted))).containsEntry("after", 3);
    }

    /** 목록을 읽은 뒤 그 세션이 중단돼 회차 조회에서 빠져도 목록이 깨지지 않습니다. */
    @Test
    void 회차_조회에_없는_재연습도_앞선_재연습_수로_센다() {
        InterviewSession root = session("root", null, 0);
        InterviewSession justAborted = session("aborted", root, 5);
        when(sessionRepository.findRetriesOf(USER_ID, List.of("root"), SessionStatus.ABORTED))
                .thenReturn(List.of(session("second", root, 1)));

        assertThat(service.roundsOf(USER_ID, List.of(justAborted))).containsEntry("aborted", 3);
    }

    @Test
    void 재연습이_없으면_회차_조회를_하지_않는다() {
        assertThat(service.roundsOf(USER_ID, List.of(session("root", null, 0)))).containsEntry("root", 1);

        verify(sessionRepository, never()).findRetriesOf(anyString(), anyCollection(), any());
    }

    @Test
    void 검색어는_소문자로_바꾸고_특수문자를_이스케이프해_부분일치로_찾는다() {
        service.findRecords(USER_ID, List.of(SessionStatus.COMPLETED), null, "  QA 100%_! ", Pageable.unpaged());

        verify(sessionRepository).findRecords(eq(USER_ID), any(), eq(null), eq("%qa 100!%!_!!%"), any());
    }

    @Test
    void 검색어가_비어_있으면_모두_찾는다() {
        service.findRecords(USER_ID, List.of(SessionStatus.COMPLETED), null, " ", Pageable.unpaged());

        verify(sessionRepository).findRecords(eq(USER_ID), any(), eq(null), eq("%"), any());
    }

    private static InterviewSession session(String id, InterviewSession root, int minutes) {
        return InterviewSession.builder().sessionId(id).retryOfSession(root)
                .status(SessionStatus.COMPLETED).createdAt(T0.plusMinutes(minutes)).build();
    }
}
