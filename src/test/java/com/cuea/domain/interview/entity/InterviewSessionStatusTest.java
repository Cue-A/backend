package com.cuea.domain.interview.entity;

import com.cuea.domain.document.entity.Document;
import com.cuea.domain.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 세션 상태 전이 가드를 검증합니다. (Issue #25)
 *
 * <p>종료 상태({@code COMPLETED}·{@code ABORTED})는 최종 상태입니다. 한 번 종료된
 * 세션이 다른 종료 상태로 뒤집히면(예: 완료된 세션이 중단으로) 리포트 대상 판정이
 * 흔들리고, 폴링 결과 도착과 사용자 abort 가 경합할 때 상태가 역전됩니다.
 *
 * <p>전이 규칙:
 * <ul>
 *   <li>IN_PROGRESS → COMPLETED 허용</li>
 *   <li>IN_PROGRESS → ABORTED 허용</li>
 *   <li>COMPLETED → ABORTED 금지 (역전 방지)</li>
 *   <li>ABORTED → COMPLETED 금지 (역전 방지)</li>
 *   <li>ABORTED → ABORTED / COMPLETED → COMPLETED 멱등(no-op)</li>
 * </ul>
 */
class InterviewSessionStatusTest {

    private InterviewSession inProgressSession() {
        return InterviewSession.builder()
                .sessionId("sess_1")
                .user(mock(User.class))
                .document(mock(Document.class))
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(9)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(SessionStatus.IN_PROGRESS)
                .build();
    }

    @Test
    void IN_PROGRESS_는_COMPLETED_로_전이할_수_있다() {
        InterviewSession session = inProgressSession();

        session.complete();

        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getCompletedAt()).isNotNull();
    }

    @Test
    void IN_PROGRESS_는_ABORTED_로_전이할_수_있다() {
        InterviewSession session = inProgressSession();

        session.abort();

        assertThat(session.getStatus()).isEqualTo(SessionStatus.ABORTED);
    }

    @Test
    void COMPLETED_세션은_ABORTED_로_역전되지_않는다() {
        // 폴링이 session_end 로 COMPLETED 를 만든 직후 사용자 abort 가 도착해도
        // 완료된 세션을 중단으로 되돌리지 않는다(리포트 대상이 사라지면 안 됨).
        InterviewSession session = inProgressSession();
        session.complete();

        session.abort();

        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    void ABORTED_세션은_COMPLETED_로_역전되지_않는다() {
        // 사용자 abort 로 ABORTED 가 된 뒤 뒤늦게 폴링 결과(session_end)가 도착해도
        // 중단된 세션을 완료로 되돌리지 않는다.
        InterviewSession session = inProgressSession();
        session.abort();

        session.complete();

        assertThat(session.getStatus()).isEqualTo(SessionStatus.ABORTED);
        assertThat(session.getCompletedAt()).isNull();
    }

    @Test
    void 이미_ABORTED_인_세션의_abort_는_멱등이다() {
        InterviewSession session = inProgressSession();
        session.abort();

        session.abort();

        assertThat(session.getStatus()).isEqualTo(SessionStatus.ABORTED);
    }

    @Test
    void 이미_COMPLETED_인_세션의_complete_는_멱등이다() {
        InterviewSession session = inProgressSession();
        session.complete();

        session.complete();

        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
    }
}
