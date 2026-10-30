package com.cuea.domain.company.repository;

import com.cuea.domain.company.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@code company} 는 소유자 개념이 없는 공용 마스터입니다.
 * docs/01-conventions.md 참고 — {@code JpaRepository} 를 그대로 씁니다.
 */
public interface CompanyRepository extends JpaRepository<Company, Long> {

    /** verified=false 기업은 서비스에 노출하지 않습니다. */
    Optional<Company> findByCompanyIdAndVerifiedTrue(Long companyId);

    /**
     * 면접 설정 화면의 기업 선택 목록. verified=true 기업만, 기업명 오름차순입니다.
     *
     * <p>정렬 키 {@code companyName} 은 UNIQUE 라 같은 값이 없으므로 순서가
     * 안정적입니다(동률로 순서가 흔들리지 않습니다). verified 필터는 서비스가
     * 아니라 쿼리에서 겁니다(Issue #85).
     */
    List<Company> findByVerifiedTrueOrderByCompanyNameAsc();
}
