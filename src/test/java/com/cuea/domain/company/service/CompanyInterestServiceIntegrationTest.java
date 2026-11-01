package com.cuea.domain.company.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.entity.Company;
import com.cuea.domain.company.entity.ValuesFormat;
import com.cuea.domain.company.repository.CompanyRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.support.PostgresRepositoryTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관심 등록의 <b>트랜잭션 커밋 동작</b>을 실제 PostgreSQL 로 검증합니다.
 *
 * <p>왜 mock 서비스 테스트로 부족한가: 중복 등록을 JPA {@code save} +
 * {@code try/catch(DataIntegrityViolationException)} 로 처리하면, 예외를 삼켜도
 * 트랜잭션이 rollback-only 로 표시돼 <b>커밋 시점에
 * {@code UnexpectedRollbackException}(500)</b> 이 납니다. mock 은 실제 트랜잭션
 * 경계와 커밋이 없어 이 문제가 드러나지 않습니다. 그래서 실제 서비스 빈을 띄워
 * {@code register} 를 <b>자체 트랜잭션으로 커밋</b>시킨 뒤 결과를 확인합니다.
 *
 * <p>테스트 메서드는 {@code NOT_SUPPORTED} 로 트랜잭션 밖에서 돌립니다. 그래야
 * 서비스의 {@code @Transactional} 이 진짜 물리 트랜잭션을 열고 커밋합니다
 * ({@code @DataJpaTest} 기본 트랜잭션 안이면 서비스가 그 트랜잭션에 합류해 커밋이
 * 일어나지 않아 검증이 무의미해집니다). 싱글턴 컨테이너를 공유하므로 넣은 데이터는
 * {@code @AfterEach} 에서 직접 지웁니다.
 */
@Import(CompanyInterestService.class)
class CompanyInterestServiceIntegrationTest extends PostgresRepositoryTest {

    @Autowired
    private CompanyInterestService service;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private String userId;
    private Long verifiedCompanyId;
    private Long unverifiedCompanyId;

    @BeforeEach
    void setUp() {
        // 사용자·기업은 register 의 별도 트랜잭션에서 FK 로 보여야 하므로 먼저 커밋해 둡니다.
        // 테스트 메서드가 NOT_SUPPORTED 라 여기서 커밋하지 않으면 register 가 FK 위반을 냅니다.
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            userId = insertUser("관심자");
            verifiedCompanyId = insertCompany("검증기업-" + System.nanoTime(), true);
            unverifiedCompanyId = insertCompany("미검증기업-" + System.nanoTime(), false);
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from user_company_interest where user_id = ?", userId);
        jdbc.update("delete from company where company_id in (?, ?)", verifiedCompanyId, unverifiedCompanyId);
        jdbc.update("delete from users where user_id = ?", userId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 처음_등록하면_행이_하나_생기고_커밋된다() {
        service.register(userId, verifiedCompanyId);

        assertThat(countInterests()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_기업을_두_번_등록해도_UnexpectedRollback_없이_멱등_성공한다() {
        service.register(userId, verifiedCompanyId);

        // 두 번째 등록이 rollback-only 로 500 이 나지 않아야 한다(이 테스트가 핵심).
        assertThatCode(() -> service.register(userId, verifiedCompanyId)).doesNotThrowAnyException();

        // 중복 행 없이 하나만 커밋돼 있다.
        assertThat(countInterests()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 미검증_기업_등록은_COMPANY_NOT_FOUND_이고_행이_생기지_않는다() {
        assertThatThrownBy(() -> service.register(userId, unverifiedCompanyId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMPANY_NOT_FOUND);

        assertThat(countInterests()).isZero();
    }

    private long countInterests() {
        Long n = jdbc.queryForObject(
                "select count(*) from user_company_interest where user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }

    private String insertUser(String nickname) {
        User user = User.create(null, nickname);
        jdbc.update("insert into users (user_id, nickname, created_at) values (?, ?, now())",
                user.getUserId(), nickname);
        return user.getUserId();
    }

    private Long insertCompany(String name, boolean verified) {
        Company c = Company.builder()
                .companyName(name)
                .industry("반도체/메모리")
                .valuesFormat(ValuesFormat.WORD)
                .coreValues(List.of(Map.of("name", "도전", "indicator", "두려워하지 않는다.")))
                .sourceUrl(List.of("https://example.com"))
                .collectedOn(LocalDate.of(2026, 8, 19))
                .verified(verified)
                .build();
        companyRepository.save(c);
        return c.getCompanyId();
    }
}
