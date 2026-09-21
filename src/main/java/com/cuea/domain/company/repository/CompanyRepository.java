package com.cuea.domain.company.repository;

import com.cuea.domain.company.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@code company} 는 소유자 개념이 없는 공용 마스터입니다.
 * docs/01-conventions.md 참고 — {@code JpaRepository} 를 그대로 씁니다.
 */
public interface CompanyRepository extends JpaRepository<Company, Long> {

    /** verified=false 기업은 서비스에 노출하지 않습니다. */
    Optional<Company> findByCompanyIdAndVerifiedTrue(Long companyId);
}
