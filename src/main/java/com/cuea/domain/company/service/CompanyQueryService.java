package com.cuea.domain.company.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.dto.response.CompanyDetailResponse;
import com.cuea.domain.company.dto.response.CompanyResponse;
import com.cuea.domain.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 기업 조회. <b>verified=true 기업만</b> 돌려줍니다.
 *
 * <p>기업 데이터는 소유자가 없는 공용 마스터라(docs/02-database.md) 사용자별
 * 스코프가 없습니다. 조회 전용이며 AI 서버를 호출하지 않고 Company 테이블만
 * 직접 봅니다(Redis 캐시·AI 프록시 없음).
 */
@Service
@RequiredArgsConstructor
public class CompanyQueryService {

    private final CompanyRepository companyRepository;

    /**
     * 면접 설정 드롭다운용 목록. verified=true 기업만, 기업명 오름차순입니다.
     * 비어 있으면 빈 목록을 그대로 돌려줍니다(예외가 아닙니다).
     *
     * <p>리포지토리의 생성자 프로젝션({@code select new ...})이 {@code companyId}·
     * {@code name} 두 컬럼만 읽어 바로 DTO 를 채우므로 서비스는 매핑 없이 위임합니다.
     */
    @Transactional(readOnly = true)
    public List<CompanyResponse> list() {
        return companyRepository.findVerifiedCompanyList();
    }

    /**
     * 기업 상세. 인재상·핵심 가치까지 담아 돌려줍니다(Issue #87).
     *
     * <p>목록과 달리 상세는 인재상 jsonb 까지 쓰므로 엔티티 전체를 읽는
     * {@code findByCompanyIdAndVerifiedTrue} 를 그대로 재사용합니다(프로젝션 아님).
     * 없는 기업과 verified=false 기업을 구별하지 않고 둘 다
     * {@code COMPANY_NOT_FOUND} 입니다. verified 필터는 서비스가 아니라 쿼리에서
     * 겁니다(목록과 같은 방식).
     */
    @Transactional(readOnly = true)
    public CompanyDetailResponse detail(Long companyId) {
        return companyRepository.findByCompanyIdAndVerifiedTrue(companyId)
                .map(CompanyDetailResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_NOT_FOUND));
    }
}
