package com.cuea.domain.company.repository;

import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.UserCompanyInterest;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관심 기업의 소유자 스코프와 멱등 삽입(ON CONFLICT)이 실제 PostgreSQL 에서
 * 작동하는지 봅니다. mock 리포지토리로는 WHERE(userId) 조건·UNIQUE(user_id,
 * company_id)·{@code on conflict do nothing} 가 검증되지 않습니다
 * (docs/01-conventions.md).
 *
 * <p>등록은 {@code insertIfAbsent} (네이티브 {@code INSERT ... ON CONFLICT DO NOTHING})
 * 라, 네이티브 쿼리가 영속성 컨텍스트를 거치지 않으므로 삽입 뒤에는 {@code em.clear()}
 * 로 1차 캐시를 비우고 다시 읽습니다.
 *
 * <p>Docker 가 없으면 {@code PostgresRepositoryTest} 가 통째로 건너뜁니다.
 */
class UserCompanyInterestRepositoryTest extends PostgresRepositoryTest {

    @Autowired
    private UserCompanyInterestRepository interestRepository;

    @Autowired
    private TestEntityManager em;

    private User me;
    private User other;
    private Company companyA;
    private Company companyB;

    @BeforeEach
    void setUp() {
        me = em.persist(User.create(null, "나"));
        other = em.persist(User.create(null, "다른 사람"));
        companyA = em.persist(company("가나기업"));
        companyB = em.persist(company("다라기업"));
        em.flush();
    }

    private Company company(String name) {
        return companyWithVerified(name, true);
    }

    private Company companyWithVerified(String name, boolean verified) {
        return Company.builder()
                .companyName(name)
                .industry("반도체/메모리")
                .valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전", "indicator", "두려워하지 않는다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 8, 19))
                .verified(verified)
                .build();
    }

    /**
     * 엔티티에 verified 세터가 없어(상태 변경을 메서드로만 노출) 테스트에서는 DB 를
     * 직접 갱신하고 1차 캐시를 비운다. "수집 배치가 검증 상태를 바꾼다"는 상황을 흉내.
     */
    private void setVerified(Company company, boolean verified) {
        em.getEntityManager()
                .createQuery("update Company c set c.verified = :v where c.companyId = :id")
                .setParameter("v", verified)
                .setParameter("id", company.getCompanyId())
                .executeUpdate();
        em.clear();
    }

    private int insert(User user, Company company) {
        int affected = interestRepository.insertIfAbsent(user.getUserId(), company.getCompanyId());
        em.clear(); // 네이티브 INSERT 는 1차 캐시를 거치지 않는다
        return affected;
    }

    @Test
    void 관심을_등록하고_내_목록에서_읽는다() {
        int affected = insert(me, companyA);

        assertThat(affected).isEqualTo(1);
        assertThat(interestRepository.findByUser_UserIdOrderByCreatedAtDesc(me.getUserId()))
                .extracting(uci -> uci.getCompany().getCompanyName())
                .containsExactly("가나기업");
    }

    @Test
    void 같은_기업을_다시_등록하면_0건이고_예외가_아니다() {
        assertThat(insert(me, companyA)).isEqualTo(1);

        // ON CONFLICT DO NOTHING: 두 번째는 제약 위반을 일으키지 않고 0 건.
        assertThat(insert(me, companyA)).isZero();

        // 행은 하나뿐이다(중복 생성 없음).
        assertThat(interestRepository.findByUser_UserIdOrderByCreatedAtDesc(me.getUserId())).hasSize(1);
    }

    @Test
    void 목록은_등록_최신순이다() {
        insert(me, companyA);
        insert(me, companyB);

        // 나중에 담은 companyB 가 먼저 온다(createdAt DESC, 동률이면 id DESC).
        assertThat(interestRepository.findByUser_UserIdOrderByCreatedAtDesc(me.getUserId()))
                .extracting(uci -> uci.getCompany().getCompanyName())
                .containsExactly("다라기업", "가나기업");
    }

    @Test
    void 관심이_없으면_빈_목록() {
        assertThat(interestRepository.findByUser_UserIdOrderByCreatedAtDesc(me.getUserId())).isEmpty();
    }

    @Test
    void 내_목록에는_다른_사용자의_관심이_섞이지_않는다() {
        insert(me, companyA);
        insert(other, companyB);

        assertThat(interestRepository.findByUser_UserIdOrderByCreatedAtDesc(me.getUserId()))
                .extracting(uci -> uci.getCompany().getCompanyName())
                .containsExactly("가나기업");
    }

    @Test
    void 다른_사용자는_같은_기업을_각자_담을_수_있다() {
        // UNIQUE 는 (user_id, company_id) 복합이라 사용자가 다르면 ON CONFLICT 에 걸리지 않는다.
        assertThat(insert(me, companyA)).isEqualTo(1);
        assertThat(insert(other, companyA)).isEqualTo(1);

        assertThat(interestRepository.existsByUser_UserIdAndCompany_CompanyId(me.getUserId(), companyA.getCompanyId()))
                .isTrue();
        assertThat(interestRepository.existsByUser_UserIdAndCompany_CompanyId(other.getUserId(), companyA.getCompanyId()))
                .isTrue();
    }

    @Test
    void exists_는_소유자와_기업으로_좁힌다() {
        insert(me, companyA);

        assertThat(interestRepository.existsByUser_UserIdAndCompany_CompanyId(me.getUserId(), companyA.getCompanyId()))
                .isTrue();
        // 내가 안 담은 기업
        assertThat(interestRepository.existsByUser_UserIdAndCompany_CompanyId(me.getUserId(), companyB.getCompanyId()))
                .isFalse();
        // 남이 담은 것은 내 exists 에 잡히지 않는다
        assertThat(interestRepository.existsByUser_UserIdAndCompany_CompanyId(other.getUserId(), companyA.getCompanyId()))
                .isFalse();
    }

    @Test
    void 해제는_내_관심만_지운다() {
        insert(me, companyA);
        insert(other, companyA);

        int deleted = interestRepository.deleteByUser_UserIdAndCompany_CompanyId(me.getUserId(), companyA.getCompanyId());
        em.clear();

        assertThat(deleted).isEqualTo(1);
        // 내 것은 사라지고 남의 것은 그대로다.
        assertThat(interestRepository.existsByUser_UserIdAndCompany_CompanyId(me.getUserId(), companyA.getCompanyId()))
                .isFalse();
        assertThat(interestRepository.existsByUser_UserIdAndCompany_CompanyId(other.getUserId(), companyA.getCompanyId()))
                .isTrue();
    }

    @Test
    void 없던_관심을_해제하면_0건이고_예외가_아니다() {
        int deleted = interestRepository.deleteByUser_UserIdAndCompany_CompanyId(me.getUserId(), companyA.getCompanyId());

        assertThat(deleted).isZero();
    }

    @Test
    void 목록은_verified_true_기업만_반환한다() {
        Company unverified = em.persist(companyWithVerified("미검증기업", false));
        em.flush();

        insert(me, companyA);    // verified=true
        insert(me, unverified);  // verified=false

        // verified=false 기업은 목록에서 숨고, verified=true 만 남는다.
        assertThat(interestRepository.findByUser_UserIdOrderByCreatedAtDesc(me.getUserId()))
                .extracting(uci -> uci.getCompany().getCompanyName())
                .containsExactly("가나기업");
    }

    @Test
    void 미검증_기업은_목록에서_숨어도_관심_행은_남는다() {
        insert(me, companyA);
        setVerified(companyA, false);

        // 목록에는 안 보인다.
        assertThat(interestRepository.findByUser_UserIdOrderByCreatedAtDesc(me.getUserId())).isEmpty();
        // 하지만 UserCompanyInterest 행 자체는 삭제되지 않았다.
        assertThat(interestRepository.existsByUser_UserIdAndCompany_CompanyId(me.getUserId(), companyA.getCompanyId()))
                .isTrue();
    }

    @Test
    void 다시_verified_true_가_되면_관심_목록에_다시_나타난다() {
        insert(me, companyA);

        setVerified(companyA, false);
        assertThat(interestRepository.findByUser_UserIdOrderByCreatedAtDesc(me.getUserId())).isEmpty();

        // 수집 배치가 다시 검증하면(verified=true) 담아둔 관심이 그대로 돌아온다.
        setVerified(companyA, true);
        assertThat(interestRepository.findByUser_UserIdOrderByCreatedAtDesc(me.getUserId()))
                .extracting(uci -> uci.getCompany().getCompanyName())
                .containsExactly("가나기업");
    }
}
