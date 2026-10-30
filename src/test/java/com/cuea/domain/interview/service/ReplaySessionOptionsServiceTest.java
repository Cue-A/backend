package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.dto.response.ReplaySessionOptionsResponse;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReplaySessionOptionsService} 조회 흐름.
 *
 * <p>검증 관점:
 * <ul>
 *   <li>전달 회차와 무관하게 최초(root) 세션 옵션을 반환한다(2·3회차 ID 로 조회해도 1회차 값)</li>
 *   <li>소유권·root 조회는 {@link InterviewSessionQueryService} 를 재사용한다(중복 구현 없음)</li>
 *   <li>완료되지 않은 세션은 {@code SESSION_NOT_COMPLETED}(409)로 거부한다</li>
 *   <li>없거나 남의 세션·root 는 {@code getOwnedSession}/{@code getRootForReplay} 가 404 를 낸다</li>
 *   <li>DTO 가 재연습 시작 요청과 같은 직렬화 형식을 유지한다(persona enum 이름, documentId UUID 문자열)</li>
 *   <li>연습 모드(기업 미선택)는 companyId·companyName 이 null 인 정상 응답</li>
 * </ul>
 *
 * <p>이 서비스는 {@link InterviewSessionQueryService} 하나에만 의존합니다. AI 클라이언트
 * 의존성이 아예 없어 "조회는 AI 를 호출하지 않는다"가 구조적으로 보장됩니다(별도 verify 불필요).
 */
class ReplaySessionOptionsServiceTest {

    private static final String USER_ID = "user-1";
    private static final String OTHER_USER_ID = "user-2";
    private static final UUID DOC_PUBLIC_ID = UUID.randomUUID();

    private InterviewSessionQueryService queryService;
    private ReplaySessionOptionsService service;

    @BeforeEach
    void setUp() {
        queryService = mock(InterviewSessionQueryService.class);
        service = new ReplaySessionOptionsService(queryService);
    }

    private Document document(UUID publicId, String title) {
        return Document.builder()
                .docId(1L)
                .publicId(publicId)
                .docTitle(title)
                .build();
    }

    private Company company(Long id, String name) {
        return Company.builder()
                .companyId(id)
                .companyName(name)
                .build();
    }

    /**
     * root 세션 빌더. {@code retryOfSession} 을 주면 이 세션은 그 회차의 자식(재연습)이 됩니다.
     */
    private InterviewSession session(String sessionId, InterviewSession retryOf,
                                     Document document, Company company,
                                     SessionStatus status) {
        return InterviewSession.builder()
                .sessionId(sessionId)
                .retryOfSession(retryOf)
                .document(document)
                .company(company)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(6)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(status)
                .build();
    }

    @Test
    void 최초_1회차_세션으로_조회하면_그_세션의_옵션을_반환한다() {
        Document doc = document(DOC_PUBLIC_ID, "홍길동 자소서");
        Company comp = company(12L, "현대엔지니어링");
        InterviewSession root = session("sess_root", null, doc, comp, SessionStatus.COMPLETED);

        when(queryService.getOwnedSession("sess_root", USER_ID)).thenReturn(root);
        when(queryService.getRootForReplay("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_root");

        assertThat(response.rootSessionId()).isEqualTo("sess_root");
        assertThat(response.documentId()).isEqualTo(DOC_PUBLIC_ID.toString());
        assertThat(response.companyId()).isEqualTo(12L);
        assertThat(response.jobRole()).isEqualTo("백엔드 개발");
        assertThat(response.questionCount()).isEqualTo(6);
        assertThat(response.persona()).isEqualTo(Persona.PRESSURE);
        assertThat(response.documentTitle()).isEqualTo("홍길동 자소서");
        assertThat(response.companyName()).isEqualTo("현대엔지니어링");
    }

    @Test
    void 재연습_2회차_세션으로_조회해도_최초_세션_옵션을_반환한다() {
        Document rootDoc = document(DOC_PUBLIC_ID, "홍길동 자소서");
        Company rootComp = company(12L, "현대엔지니어링");
        InterviewSession root = session("sess_root", null, rootDoc, rootComp, SessionStatus.COMPLETED);
        // 2회차는 retryOfSession 으로 root 를 가리킵니다.
        InterviewSession second = session("sess_2nd", root, rootDoc, rootComp, SessionStatus.COMPLETED);

        when(queryService.getOwnedSession("sess_2nd", USER_ID)).thenReturn(second);
        when(queryService.getRootForReplay("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_2nd");

        // 전달한 ID 는 2회차지만 root 로 환원해 조회합니다.
        verify(queryService).getRootForReplay("sess_root", USER_ID);
        assertThat(response.rootSessionId()).isEqualTo("sess_root");
    }

    @Test
    void 재연습_3회차_세션으로_조회해도_최초_세션_옵션을_반환한다() {
        Document rootDoc = document(DOC_PUBLIC_ID, "홍길동 자소서");
        InterviewSession root = session("sess_root", null, rootDoc, null, SessionStatus.COMPLETED);
        // 3회차도 retryOfSession 이 (2회차가 아니라) 최초 root 를 가리킵니다.
        InterviewSession third = session("sess_3rd", root, rootDoc, null, SessionStatus.COMPLETED);

        when(queryService.getOwnedSession("sess_3rd", USER_ID)).thenReturn(third);
        when(queryService.getRootForReplay("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_3rd");

        verify(queryService).getRootForReplay("sess_root", USER_ID);
        assertThat(response.rootSessionId()).isEqualTo("sess_root");
    }

    @Test
    void 기업을_고르지_않은_연습모드_세션은_companyId_companyName_이_null() {
        Document doc = document(DOC_PUBLIC_ID, "직접작성 이력서");
        InterviewSession root = session("sess_root", null, doc, null, SessionStatus.COMPLETED);

        when(queryService.getOwnedSession("sess_root", USER_ID)).thenReturn(root);
        when(queryService.getRootForReplay("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_root");

        assertThat(response.companyId()).isNull();
        assertThat(response.companyName()).isNull();
        // documentId 는 NOT NULL 연관이라 연습 모드여도 채워집니다.
        assertThat(response.documentId()).isEqualTo(DOC_PUBLIC_ID.toString());
    }

    @Test
    void 고정_옵션은_재연습_시작_요청과_같은_직렬화_형식을_유지한다() {
        Document doc = document(DOC_PUBLIC_ID, "자소서");
        Company comp = company(7L, "네이버");
        InterviewSession root = session("sess_root", null, doc, comp, SessionStatus.COMPLETED);

        when(queryService.getOwnedSession("sess_root", USER_ID)).thenReturn(root);
        when(queryService.getRootForReplay("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_root");

        // persona 는 enum 그대로(FRIENDLY/PRESSURE) — InterviewStartRequest.persona 와 동일.
        assertThat(response.persona()).isInstanceOf(Persona.class);
        assertThat(response.persona().name()).isIn("FRIENDLY", "PRESSURE");
        // documentId 는 UUID 문자열이라 UUID.fromString 으로 재연습 시작 요청에 그대로 쓸 수 있습니다.
        assertThat(UUID.fromString(response.documentId())).isEqualTo(DOC_PUBLIC_ID);
        // companyId 는 Long.
        assertThat(response.companyId()).isInstanceOf(Long.class);
    }

    @Test
    void 완료되지_않은_IN_PROGRESS_세션은_409로_거부한다() {
        Document doc = document(DOC_PUBLIC_ID, "자소서");
        InterviewSession inProgress = session("sess_1", null, doc, null, SessionStatus.IN_PROGRESS);

        when(queryService.getOwnedSession("sess_1", USER_ID)).thenReturn(inProgress);

        assertThatThrownBy(() -> service.getReplayOptions(USER_ID, "sess_1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_COMPLETED);

        // 완료 전이면 root 조회로 넘어가지 않습니다.
        verify(queryService, never()).getRootForReplay(anyString(), anyString());
    }

    @Test
    void 완료되지_않은_ABORTED_세션은_409로_거부한다() {
        Document doc = document(DOC_PUBLIC_ID, "자소서");
        InterviewSession aborted = session("sess_1", null, doc, null, SessionStatus.ABORTED);

        when(queryService.getOwnedSession("sess_1", USER_ID)).thenReturn(aborted);

        assertThatThrownBy(() -> service.getReplayOptions(USER_ID, "sess_1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_COMPLETED);

        verify(queryService, never()).getRootForReplay(anyString(), anyString());
    }

    @Test
    void 존재하지_않거나_남의_세션이면_getOwnedSession_이_404를_낸다() {
        when(queryService.getOwnedSession("missing", USER_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> service.getReplayOptions(USER_ID, "missing"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        verify(queryService, never()).getRootForReplay(anyString(), anyString());
    }

    @Test
    void root_가_남의_세션이거나_없으면_getRootForReplay_이_404를_낸다() {
        Document doc = document(DOC_PUBLIC_ID, "자소서");
        // 2회차는 본인 소유로 조회되지만, root 가 다른 사용자/깨진 참조라 getRootForReplay 가 비어 404.
        InterviewSession root = session("sess_root", null, doc, null, SessionStatus.COMPLETED);
        InterviewSession second = session("sess_2nd", root, doc, null, SessionStatus.COMPLETED);

        when(queryService.getOwnedSession("sess_2nd", USER_ID)).thenReturn(second);
        when(queryService.getRootForReplay(eq("sess_root"), eq(USER_ID)))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> service.getReplayOptions(USER_ID, "sess_2nd"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 소유권_검증과_root_조회를_재사용한다() {
        Document doc = document(DOC_PUBLIC_ID, "자소서");
        InterviewSession root = session("sess_root", null, doc, null, SessionStatus.COMPLETED);

        when(queryService.getOwnedSession("sess_root", USER_ID)).thenReturn(root);
        when(queryService.getRootForReplay("sess_root", USER_ID)).thenReturn(root);

        service.getReplayOptions(USER_ID, "sess_root");

        // 전달 세션 소유권과 root 소유권 둘 다 같은 userId 로 검증됩니다.
        verify(queryService).getOwnedSession("sess_root", USER_ID);
        verify(queryService).getRootForReplay("sess_root", USER_ID);
    }
}
