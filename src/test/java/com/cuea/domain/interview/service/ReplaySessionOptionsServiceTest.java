package com.cuea.domain.interview.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.dto.response.ReplaySessionOptionsResponse;
import com.cuea.domain.interview.dto.response.ReplayBlockReason;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReplaySessionOptionsService} 조회 흐름.
 *
 * <p>이 서비스는 소유권·완료 상태·root 결정을 직접 하지 않고, 재연습 시작 흐름과 공유하는
 * {@link InterviewSessionQueryService#getReplayRoot} 하나에 위임합니다(중복 구현 없음).
 * 그래서 여기서는 (1) 그 공용 메서드에 경로 변수·사용자 ID 를 그대로 넘기고 반환한 root 를
 * DTO 로 변환하는지, (2) 공용 메서드가 던지는 예외(404·409)를 가로채지 않고 그대로
 * 전파하는지, (3) DTO 직렬화 형식이 재연습 시작 요청과 맞는지를 봅니다.
 *
 * <p>소유권·완료 상태·root fetch join 같은 {@code getReplayRoot} <b>자체</b>의 단계별 동작은
 * {@link InterviewSessionQueryServiceTest} 와 {@code InterviewSessionReplayOptionsRepositoryTest}
 * 가 검증합니다(여기서 중복 검증하지 않습니다).
 *
 * <p>AI 클라이언트 의존성이 아예 없어 "조회는 AI 를 호출하지 않는다"가 구조적으로 보장됩니다.
 */
class ReplaySessionOptionsServiceTest {

    private static final String USER_ID = "user-1";
    private static final UUID DOC_PUBLIC_ID = UUID.randomUUID();

    private InterviewSessionQueryService queryService;
    private ReplaySessionOptionsService service;

    @BeforeEach
    void setUp() {
        queryService = mock(InterviewSessionQueryService.class);
        service = new ReplaySessionOptionsService(queryService);
    }

    private Document document(UUID publicId, String title) {
        return document(publicId, title, false);
    }

    private Document document(UUID publicId, String title, boolean deleted) {
        Document doc = Document.builder()
                .docId(1L)
                .publicId(publicId)
                .docTitle(title)
                .build();
        if (deleted) {
            doc.markDeleted();
        }
        return doc;
    }

    private Company company(Long id, String name) {
        return company(id, name, true);
    }

    private Company company(Long id, String name, boolean verified) {
        return Company.builder()
                .companyId(id)
                .companyName(name)
                .verified(verified)
                .build();
    }

    private InterviewSession root(String sessionId, Document document, Company company) {
        return InterviewSession.builder()
                .sessionId(sessionId)
                .document(document)
                .company(company)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(6)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(SessionStatus.COMPLETED)
                .build();
    }

    @Test
    void 공용_getReplayRoot_가_돌려준_root_세션의_옵션을_DTO_로_반환한다() {
        Document doc = document(DOC_PUBLIC_ID, "홍길동 자소서");
        Company comp = company(12L, "현대엔지니어링");
        InterviewSession root = root("sess_root", doc, comp);

        when(queryService.getReplayRoot("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_root");

        assertThat(response.rootSessionId()).isEqualTo("sess_root");
        assertThat(response.documentId()).isEqualTo(DOC_PUBLIC_ID.toString());
        assertThat(response.companyId()).isEqualTo(12L);
        assertThat(response.jobRole()).isEqualTo("백엔드 개발");
        assertThat(response.questionCount()).isEqualTo(6);
        assertThat(response.persona()).isEqualTo(Persona.PRESSURE);
        assertThat(response.documentTitle()).isEqualTo("홍길동 자소서");
        assertThat(response.companyName()).isEqualTo("현대엔지니어링");
        // 유효한 문서 + 검증된 기업 → 바로 재연습 가능.
        assertThat(response.replayable()).isTrue();
        assertThat(response.replayBlockReasons()).isEmpty();
    }

    @Test
    void 전달한_세션ID_와_사용자ID_를_공용_getReplayRoot_에_그대로_넘긴다() {
        // 2·3회차 ID 로 조회해도 서비스는 그 ID 를 그대로 getReplayRoot 에 넘기고,
        // 최초 root 로 환원하는 책임은 getReplayRoot 가 진다(응답 rootSessionId 는 root 값).
        Document doc = document(DOC_PUBLIC_ID, "홍길동 자소서");
        InterviewSession root = root("sess_root", doc, null);

        when(queryService.getReplayRoot("sess_2nd", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_2nd");

        verify(queryService).getReplayRoot("sess_2nd", USER_ID);
        assertThat(response.rootSessionId()).isEqualTo("sess_root");
    }

    @Test
    void 기업을_고르지_않은_연습모드_root_는_companyId_companyName_이_null() {
        Document doc = document(DOC_PUBLIC_ID, "직접작성 이력서");
        InterviewSession root = root("sess_root", doc, null);

        when(queryService.getReplayRoot("sess_root", USER_ID)).thenReturn(root);

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
        InterviewSession root = root("sess_root", doc, comp);

        when(queryService.getReplayRoot("sess_root", USER_ID)).thenReturn(root);

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
    void 공용_getReplayRoot_의_404_예외를_가로채지_않고_그대로_전파한다() {
        when(queryService.getReplayRoot("missing", USER_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> service.getReplayOptions(USER_ID, "missing"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 공용_getReplayRoot_의_409_예외를_가로채지_않고_그대로_전파한다() {
        when(queryService.getReplayRoot("sess_in_progress", USER_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_COMPLETED));

        assertThatThrownBy(() -> service.getReplayOptions(USER_ID, "sess_in_progress"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_COMPLETED);
    }

    // ───────────── 재연습 가능 여부(replayable / replayBlockReasons) ─────────────

    @Test
    void 연습모드_유효문서_root_는_기업이_없어도_replayable_true() {
        // 기업 미선택 연습 모드: 문서만 유효하면 시작 API 가 거부하지 않으므로 replayable.
        Document doc = document(DOC_PUBLIC_ID, "직접작성 이력서");
        InterviewSession root = root("sess_root", doc, null);

        when(queryService.getReplayRoot("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_root");

        assertThat(response.replayable()).isTrue();
        assertThat(response.replayBlockReasons()).isEmpty();
    }

    @Test
    void 삭제된_문서가_연결된_root_는_옵션은_내려가되_replayable_false_와_DOCUMENT_DELETED_사유() {
        // 조회는 막지 않는다(옵션은 그대로). 다만 시작 API 는 DOCUMENT_NOT_FOUND 로 거부하므로 미리 안내.
        Document deletedDoc = document(DOC_PUBLIC_ID, "삭제된 자소서", true);
        Company comp = company(12L, "현대엔지니어링", true);
        InterviewSession root = root("sess_root", deletedDoc, comp);

        when(queryService.getReplayRoot("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_root");

        // 옵션 자체는 그대로 조회된다(조회/시작 분리 유지).
        assertThat(response.documentId()).isEqualTo(DOC_PUBLIC_ID.toString());
        assertThat(response.documentTitle()).isEqualTo("삭제된 자소서");
        // 재연습은 불가하고 사유가 내려간다.
        assertThat(response.replayable()).isFalse();
        assertThat(response.replayBlockReasons())
                .extracting(ReplaySessionOptionsResponse.ReplayBlockReasonItem::code)
                .containsExactly(ReplayBlockReason.DOCUMENT_DELETED);
        assertThat(response.replayBlockReasons().get(0).message()).isNotBlank();
    }

    @Test
    void verified_false_기업이_연결된_root_는_옵션은_내려가되_replayable_false_와_COMPANY_UNVERIFIED_사유() {
        Document doc = document(DOC_PUBLIC_ID, "자소서");
        Company unverified = company(99L, "미검증기업", false);
        InterviewSession root = root("sess_root", doc, unverified);

        when(queryService.getReplayRoot("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_root");

        assertThat(response.companyId()).isEqualTo(99L);
        assertThat(response.companyName()).isEqualTo("미검증기업");
        assertThat(response.replayable()).isFalse();
        assertThat(response.replayBlockReasons())
                .extracting(ReplaySessionOptionsResponse.ReplayBlockReasonItem::code)
                .containsExactly(ReplayBlockReason.COMPANY_UNVERIFIED);
    }

    @Test
    void 삭제문서와_미검증기업이_동시면_두_사유를_모두_담는다() {
        Document deletedDoc = document(DOC_PUBLIC_ID, "삭제된 자소서", true);
        Company unverified = company(99L, "미검증기업", false);
        InterviewSession root = root("sess_root", deletedDoc, unverified);

        when(queryService.getReplayRoot("sess_root", USER_ID)).thenReturn(root);

        ReplaySessionOptionsResponse response = service.getReplayOptions(USER_ID, "sess_root");

        assertThat(response.replayable()).isFalse();
        assertThat(response.replayBlockReasons())
                .extracting(ReplaySessionOptionsResponse.ReplayBlockReasonItem::code)
                .containsExactlyInAnyOrder(
                        ReplayBlockReason.DOCUMENT_DELETED,
                        ReplayBlockReason.COMPANY_UNVERIFIED);
    }
}
