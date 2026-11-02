package com.cuea.domain.company.repository;

import com.cuea.domain.company.entity.UserCompanyInterest;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 관심 기업. <b>소유자가 있는 엔티티라 {@code JpaRepository} 를 상속하지 않습니다.</b>
 *
 * <p>상속하면 {@code findById}·{@code findAll}·{@code deleteById} 가 같이 딸려옵니다.
 * 소유자 스코프 쿼리를 만들어 둬도 옆에 있는 {@code findById} 를 그냥 쓰면 그대로
 * 뚫립니다. 필요한 메서드만 열어두면 쓰려고 해도 컴파일이 안 됩니다.
 * {@code docs/01-conventions.md} 의 "소유자 있는 엔티티는 Repository 를 상속합니다" 참고.
 *
 * <p><b>조회·삭제는 항상 소유자({@code userId})와 함께 겁니다.</b> 조회해놓고
 * 서비스에서 소유자를 비교하는 방식은 비교를 한 번 빠뜨리면 남의 데이터가 그대로
 * 열리거나 지워집니다. 쿼리에 묶어두면 잊을 수가 없습니다(문서 도메인과 같은 규칙).
 */
public interface UserCompanyInterestRepository extends Repository<UserCompanyInterest, Long> {

    /**
     * 관심 등록. <b>이미 있으면 아무 일도 하지 않습니다(멱등).</b>
     *
     * <p>{@code INSERT ... ON CONFLICT (user_id, company_id) DO NOTHING} 를 씁니다.
     * JPA {@code save} + {@code try/catch(DataIntegrityViolationException)} 방식은
     * UNIQUE 경합 때 <b>트랜잭션이 rollback-only 로 표시돼</b> 예외를 삼켜도 커밋
     * 시점에 {@code UnexpectedRollbackException}(500)이 납니다. ON CONFLICT 는
     * 제약 위반 자체를 일으키지 않으므로 트랜잭션이 깨끗하게 유지되고, 동시 요청이
     * 몰려도 중복 행 없이 둘 다 성공합니다.
     *
     * <p>네이티브 INSERT 라 {@code @PrePersist}(BaseCreatedEntity)가 돌지 않습니다.
     * {@code created_at} 은 NOT NULL 이므로 {@code now()} 로 직접 채웁니다.
     *
     * @return 실제로 삽입된 행 수(1=새로 등록, 0=이미 있었음). 호출부는 멱등이라
     *         값으로 분기하지 않지만, 테스트가 "새 등록 vs 중복"을 구분하는 데 씁니다.
     */
    @Modifying
    @Query(value = """
            insert into user_company_interest (user_id, company_id, created_at)
            values (:userId, :companyId, now())
            on conflict (user_id, company_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId, @Param("companyId") Long companyId);

    /**
     * 내 관심 기업 목록. 등록 최신순({@code createdAt DESC}) 입니다(Issue #88).
     *
     * <p>{@code company} 를 {@code join fetch} 로 함께 가져와 목록 DTO 변환에서
     * N+1 이 나지 않게 합니다({@code company.companyName} 을 바로 읽습니다).
     *
     * <p><b>{@code verified=true} 기업만 돌려줍니다.</b> 미검증 기업은 서비스 전반에서
     * 노출하지 않는 정책(Issue #85, {@code docs/10-ai-client.md} verified 필터)과
     * 맞추고, 목록에서 눌렀을 때 상세가 {@code COMPANY_NOT_FOUND}(404) 로 어긋나는
     * 것을 막습니다.
     *
     * <p>필터는 조회에만 겁니다. <b>{@code UserCompanyInterest} 행 자체는 지우지
     * 않습니다.</b> 사용자가 담아둔 관심을 조용히 삭제하지 않으며, 기업이 다시
     * {@code verified=true} 로 돌아오면 그대로 목록에 다시 나타납니다. 등록 시점에도
     * {@code verified=true} 를 요구합니다(숨김은 표시에서만).
     */
    @Query("select uci from UserCompanyInterest uci"
            + " join fetch uci.company c"
            + " where uci.user.userId = :userId and c.verified = true"
            + " order by uci.createdAt desc, uci.id desc")
    List<UserCompanyInterest> findByUser_UserIdOrderByCreatedAtDesc(@Param("userId") String userId);

    /** 소유자+기업으로 좁힌 존재 확인. 통합 테스트의 검증용입니다. */
    boolean existsByUser_UserIdAndCompany_CompanyId(String userId, Long companyId);

    /**
     * 내 관심 해제. 지운 행 수를 돌려줍니다(0 이면 애초에 없던 것).
     *
     * <p>소유자 조건을 쿼리에 묶어 남의 관심은 건드리지 않습니다. 없던 것을 지워도
     * 0 을 돌려줄 뿐 예외가 아니라 멱등 삭제가 됩니다.
     */
    @Modifying
    @Query("delete from UserCompanyInterest uci"
            + " where uci.user.userId = :userId and uci.company.companyId = :companyId")
    int deleteByUser_UserIdAndCompany_CompanyId(@Param("userId") String userId,
                                                @Param("companyId") Long companyId);
}
