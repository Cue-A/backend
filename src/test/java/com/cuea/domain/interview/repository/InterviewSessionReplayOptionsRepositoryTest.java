package com.cuea.domain.interview.repository;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.domain.document.entity.DocType;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.Persona;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재연습 옵션 조회가 재사용하는 {@link InterviewSessionRepository#findRootForReplay} 의
 * DB 쪽 동작을 실제 PostgreSQL 로 봅니다.
 *
 * <ul>
 *   <li>fetch join 으로 {@code document}·{@code company} 가 초기화돼, 트랜잭션 밖
 *       (open-in-view=false)에서 옵션을 읽어도 {@code LazyInitializationException} 이 없음</li>
 *   <li>요청 사용자 소유 root 만 반환(교차 사용자 참조 차단)</li>
 *   <li>연습 모드(기업 미선택) root 는 company 가 null 이어도 조회됨</li>
 *   <li>삭제된 문서·{@code verified=false} 기업이 연결돼 있어도 조회는 그대로 됨
 *       (조회와 재연습 시작 가능 여부는 별개)</li>
 * </ul>
 *
 * <p>mock 리포지토리로는 fetch join·소유자 조건 같은 DB 분기가 검증되지 않아 실제 DB 로
 * 봅니다(docs/01-conventions.md 테스트 항목). Docker 가 없으면 건너뜁니다.
 */
class InterviewSessionReplayOptionsRepositoryTest extends PostgresRepositoryTest {

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private TestEntityManager em;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = em.persist(User.create(null, "면접자"));
    }

    private Document readyResume(User user) {
        return em.persist(Document.ofMarkdown(
                user, UUID.randomUUID(), DocType.RESUME, "이력서", "본문", "docs/resume.txt",
                DocumentStatus.READY));
    }

    private Company company(String name, boolean verified) {
        return em.persist(Company.builder()
                .companyName(name)
                .industry("IT")
                .valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전", "indicator", "새로운 시도를 한다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 1, 1))
                .verified(verified)
                .build());
    }

    private InterviewSession session(String id, InterviewSession retryOf, User user,
                                     Document document, Company company) {
        return em.persist(InterviewSession.builder()
                .sessionId(id)
                .retryOfSession(retryOf)
                .user(user)
                .document(document)
                .company(company)
                .mode("PRACTICE")
                .jobRole("백엔드 개발")
                .questionCount(6)
                .persona(Persona.PRESSURE)
                .hideQuestionText(false)
                .status(SessionStatus.COMPLETED)
                .build());
    }

    @Test
    void root_조회는_document_와_company_를_fetch_join_으로_초기화한다() {
        Document resume = readyResume(owner);
        Company comp = company("현대엔지니어링", true);
        session("sess_root", null, owner, resume, comp);
        em.flush();
        em.clear();

        InterviewSession found = sessionRepository
                .findRootForReplay("sess_root", owner.getUserId())
                .orElseThrow();

        // 트랜잭션 밖에서 읽어도 안전하도록 연관이 미리 초기화돼 있어야 합니다.
        assertThat(Hibernate.isInitialized(found.getDocument())).isTrue();
        assertThat(Hibernate.isInitialized(found.getCompany())).isTrue();
        assertThat(found.getDocument().getDocTitle()).isEqualTo("이력서");
        assertThat(found.getCompany().getCompanyName()).isEqualTo("현대엔지니어링");
    }

    @Test
    void 다른_사용자_소유_root_는_조회되지_않는다() {
        Document resume = readyResume(owner);
        session("sess_root", null, owner, resume, null);
        em.flush();

        User other = em.persist(User.create(null, "다른 사람"));

        assertThat(sessionRepository.findRootForReplay("sess_root", other.getUserId()))
                .isEmpty();
        assertThat(sessionRepository.findRootForReplay("sess_root", owner.getUserId()))
                .isPresent();
    }

    @Test
    void 연습모드_root_는_company_가_null_이어도_조회된다() {
        Document resume = readyResume(owner);
        session("sess_root", null, owner, resume, null);
        em.flush();
        em.clear();

        InterviewSession found = sessionRepository
                .findRootForReplay("sess_root", owner.getUserId())
                .orElseThrow();

        assertThat(found.getCompany()).isNull();
        assertThat(found.getDocument().getDocTitle()).isEqualTo("이력서");
    }

    @Test
    void 삭제된_문서가_연결된_root_도_조회된다() {
        Document resume = readyResume(owner);
        resume.markDeleted();
        em.persist(resume);
        session("sess_root", null, owner, resume, null);
        em.flush();
        em.clear();

        InterviewSession found = sessionRepository
                .findRootForReplay("sess_root", owner.getUserId())
                .orElseThrow();

        // 조회는 저장된 옵션을 그대로 보여줍니다. 삭제 여부는 조회를 막지 않습니다.
        assertThat(found.getDocument()).isNotNull();
        assertThat(found.getDocument().isDeleted()).isTrue();
    }

    @Test
    void verified_false_기업이_연결된_root_도_조회된다() {
        Document resume = readyResume(owner);
        Company unverified = company("미검증기업", false);
        session("sess_root", null, owner, resume, unverified);
        em.flush();
        em.clear();

        InterviewSession found = sessionRepository
                .findRootForReplay("sess_root", owner.getUserId())
                .orElseThrow();

        assertThat(found.getCompany()).isNotNull();
        assertThat(found.getCompany().isVerified()).isFalse();
        assertThat(found.getCompany().getCompanyName()).isEqualTo("미검증기업");
    }

    @Test
    void rootSessionId_로_조회하면_재연습_회차가_있어도_최초_세션을_읽는다() {
        Document resume = readyResume(owner);
        InterviewSession root = session("sess_root", null, owner, resume, null);
        // 2·3회차는 retryOfSession 으로 최초 root 를 가리킵니다.
        session("sess_2nd", root, owner, resume, null);
        session("sess_3rd", root, owner, resume, null);
        em.flush();
        em.clear();

        // 서비스는 어느 회차로 들어와도 rootSessionId()=sess_root 로 환원해 이 쿼리를 호출합니다.
        InterviewSession found = sessionRepository
                .findRootForReplay("sess_root", owner.getUserId())
                .orElseThrow();

        assertThat(found.getSessionId()).isEqualTo("sess_root");
        assertThat(found.isRetry()).isFalse();
    }
}
